package app.getplexo.mobile.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

enum class DownloadStatus {
    QUEUED,
    PREPARING,
    RUNNING,
    SAVING,
    DONE,
    ERROR,
    CANCELLED;

    val finished: Boolean get() = this == DONE || this == ERROR || this == CANCELLED
    val active: Boolean get() = this == PREPARING || this == RUNNING || this == SAVING
}

data class LinkProgress(
    val id: String,
    val bytes: Long,
    val speedBps: Long,
    val streams: Int,
)

data class DownloadSnapshot(
    val name: String,
    val url: String,
    val status: DownloadStatus,
    val totalBytes: Long,
    val bytesDone: Long,
    val speedBps: Long,
    val links: List<LinkProgress>,
    val parallel: Boolean,
    val message: String? = null,
    val savedTo: String? = null,
    val savedUri: String? = null,
) {
    val fraction: Float
        get() = if (totalBytes > 0) {
            (bytesDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val etaSeconds: Long?
        get() {
            if (status != DownloadStatus.RUNNING || totalBytes <= 0 || speedBps <= 0) return null
            val left = totalBytes - bytesDone
            return if (left <= 0) 0 else left / speedBps
        }

    companion object {
        fun initial(
            url: String,
            name: String?,
            status: DownloadStatus = DownloadStatus.QUEUED,
            message: String? = null,
        ) = DownloadSnapshot(
            name = name ?: "download",
            url = url,
            status = status,
            totalBytes = 0,
            bytesDone = 0,
            speedBps = 0,
            links = emptyList(),
            parallel = false,
            message = message,
        )

        fun idle() = DownloadSnapshot(
            name = "",
            url = "",
            status = DownloadStatus.PREPARING,
            totalBytes = 0,
            bytesDone = 0,
            speedBps = 0,
            links = emptyList(),
            parallel = false,
        )
    }
}

/**
 * Splits a file into byte ranges and downloads them at the same time over every
 * enabled link. Workers pull chunks from one shared cursor, so a faster radio simply
 * finishes more chunks - no manual bandwidth split needed.
 */
class DownloadTask(
    private val ctx: Context,
    private val registry: LinkRegistry,
    private val id: String,
    private val url: String,
    private val requestedName: String?,
    private val streamsFor: (String) -> Int,
    private val chunkSize: Long,
    private val allowedLinkIds: Set<String>?,
    private val limiter: SharedRateLimiter,
    private val isLinkAllowed: (String) -> Boolean,
    private val autoRetry: () -> Boolean,
    private val publish: (DownloadSnapshot) -> Unit,
) {

    private val done = AtomicLong(0)
    private val perLinkBytes = ConcurrentHashMap<String, AtomicLong>()
    private val perLinkSpeed = ConcurrentHashMap<String, Long>()
    private val activeByLink = ConcurrentHashMap<String, AtomicInteger>()
    private val failures = ConcurrentHashMap<String, AtomicInteger>()
    private val deadLinks: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val clients = ConcurrentHashMap<String, Pair<android.net.Network, OkHttpClient>>()

    @Volatile private var totalBytes = 0L
    @Volatile private var parallel = false
    @Volatile private var name = "download"
    @Volatile private var mime: String? = null

    private var speed = 0L

    /** True while every link is gone and the workers are waiting for one to return. */
    @Volatile private var waitingForLink = false

    /** Every live network this job may use: selected by the user and under any cap. */
    private fun usableLinks(): List<Link> =
        registry.usable(allowedLinkIds).filter { isLinkAllowed(it.id) }

    /**
     * Every link this job is allowed to use, connected or not, deduplicated by
     * interface. Workers for a radio that is still coming up wait for it instead of
     * being left out for the whole file.
     */
    private fun streamLinks(): List<Link> {
        val seen = HashSet<String>()
        return registry.links.filter { link ->
            val state = link.state.value
            val key = state.networkKey
            state.enabled &&
                (allowedLinkIds == null || link.id in allowedLinkIds) &&
                isLinkAllowed(link.id) &&
                (key == null || seen.add(key))
        }
    }

    /**
     * Rolling 2 s windows (timestamp, bytes) for the overall job and for each link.
     * A single 500 ms window is far too noisy on a mobile radio: it reports the
     * burst/sleep pattern of the modem, not the real throughput.
     */
    private val history = ArrayDeque<Pair<Long, Long>>()
    private val historyByLink = HashMap<String, ArrayDeque<Pair<Long, Long>>>()

    suspend fun run() {
        val part = partFile()
        try {
            publish(snap(DownloadStatus.PREPARING))
            probe()

            if (parallel) {
                part.parentFile?.mkdirs()
                part.createNewFile()
                RandomAccessFile(part, "rw").use { it.setLength(totalBytes) }
                runParallel(part)
                if (done.get() != totalBytes) {
                    throw IOException("Only ${done.get()} of $totalBytes bytes arrived")
                }
            } else {
                runSingle(part)
            }

            currentCoroutineContext().ensureActive()
            publish(snap(DownloadStatus.SAVING))
            val saved = withContext(Dispatchers.IO) { Store.save(ctx, part, name, mime) }
            part.delete()
            publish(snap(DownloadStatus.DONE, savedTo = saved.path, savedUri = saved.uri))
        } catch (e: CancellationException) {
            publish(snap(DownloadStatus.CANCELLED, message = "Cancelled"))
            runCatching { part.delete() }
            throw e
        } catch (e: Throwable) {
            // Catch Error too: leaving the state at RUNNING would strand the UI and
            // keep the foreground service alive forever.
            publish(snap(DownloadStatus.ERROR, message = e.message ?: e.javaClass.simpleName))
            runCatching { part.delete() }
        }
    }

    private fun partFile(): File =
        File(File(ctx.filesDir, "downloads").apply { mkdirs() }, "$id.part")

    /** One ranged request tells us size, range support and the real file name. */
    private suspend fun probe() = withContext(Dispatchers.IO) {
        val candidates = usableLinks()
        if (candidates.isEmpty()) {
            throw IOException(noLinksMessage())
        }

        // Try every live link: the first one can be a captive portal or a dead route
        // whatever the OS thinks about it.
        var last: Exception? = null
        for (link in candidates) {
            currentCoroutineContext().ensureActive()
            try {
                probeOn(link)
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Could not reach the server")
    }

    private fun probeOn(link: Link) {
        val network = link.network() ?: throw IOException("${link.title} disconnected")
        mime = null
        parallel = false
        totalBytes = -1L

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .get()
            .build()

        clientFor(network).newCall(request).execute().use { response ->
            if (response.code >= 400 && response.code != 416) {
                throw IOException("Server answered HTTP ${response.code}")
            }
            name = Store.sanitize(requestedName ?: nameFrom(url, response.header("Content-Disposition")))
            mime = response.header("Content-Type")?.substringBefore(';')?.trim()

            val contentRange = response.header("Content-Range")
            if (response.code == 206 && contentRange != null) {
                totalBytes = contentRange.substringAfter('/', "-1").trim().toLongOrNull() ?: -1L
                parallel = totalBytes > 0
            } else {
                totalBytes = response.body?.contentLength() ?: -1L
                parallel = false
            }
        }
    }

    private suspend fun runParallel(part: File) = coroutineScope {
        val cursor = AtomicLong(0L)
        val channel = RandomAccessFile(part, "rw").channel

        publish(snap(DownloadStatus.RUNNING))
        val ticker = launch {
            while (isActive) {
                delay(500)
                sample()
                publish(snap(DownloadStatus.RUNNING))
            }
        }

        try {
            val usable = usableLinks()
            if (usable.isEmpty()) throw IOException(noLinksMessage())

            val failed = ConcurrentLinkedQueue<ByteRange>()
            val firstError = AtomicReference<Exception?>(null)

            // Every selected link gets streams. Connected ones start now; a radio that
            // is still coming up (requestNetwork can take a few seconds) joins the same
            // cursor as soon as it is usable. Those waiters are cancelled when the file
            // is done so they cannot keep the job alive.
            val pool = streamLinks()
            val lateStop = AtomicBoolean(false)
            val lateJobs = pool.filterNot { it.usable }.flatMap { link ->
                List(streamsFor(link.id).coerceAtLeast(1)) {
                    launch(Dispatchers.IO) {
                        var waited = 0L
                        while (currentCoroutineContext().isActive && !link.usable && !lateStop.get()) {
                            if (waited >= LATE_LINK_WAIT_MS) return@launch
                            delay(500)
                            waited += 500
                        }
                        if (!link.usable || lateStop.get()) return@launch
                        worker(
                            channel,
                            link,
                            { nextRange(cursor, link) },
                            failed,
                            firstError,
                            lateStop,
                        )
                    }
                }
            }

            try {
                drain(
                    channel,
                    pool.filter { it.usable },
                    { link -> nextRange(cursor, link) },
                    failed,
                    firstError,
                )

                // The connected streams have run out of ranges. Let a late link finish
                // the range it already took, then stop it before counting the bytes.
                lateStop.set(true)
                lateJobs.forEach { it.join() }

                // A range that exhausted its attempts must not sink the whole file: park it
                // and retry the leftovers once the main pass is done.
                var round = 0
                while (failed.isNotEmpty() && round < MAX_RETRY_ROUNDS) {
                    if (done.get() == 0L) break // nothing ever worked; the server or links are down
                    round++
                    val batch = failed.toList()
                    failed.clear()
                    failures.clear()
                    deadLinks.clear()
                    delay(500L * round)
                    val links = usableLinks().ifEmpty { usable }
                    val next = AtomicInteger(0)
                    drain(channel, links, { batch.getOrNull(next.getAndIncrement()) }, failed, firstError)
                }
            } finally {
                lateJobs.forEach { it.cancel() }
            }

            if (failed.isNotEmpty()) {
                throw IOException("${failed.size} ranges still failed", firstError.get())
            }
        } finally {
            ticker.cancel()
            channel.close()
        }
    }

    /**
     * Hands out the next byte range. The size is per link and adaptive: a fast radio
     * gets large requests (fewer round trips), a slow one gets small ones so it cannot
     * sit on the tail of the file while every other link has finished.
     */
    private fun nextRange(cursor: AtomicLong, link: Link): ByteRange? {
        val size = chunkBytesFor(link)
        val start = cursor.getAndAdd(size)
        if (start >= totalBytes) return null
        return ByteRange(start, minOf(totalBytes - 1, start + size - 1))
    }

    private fun chunkBytesFor(link: Link): Long {
        val maxChunk = chunkSize.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)
        val measured = perLinkSpeed[link.id] ?: 0L
        // Nothing measured yet: start small. A Bluetooth link that grabbed the configured
        // 8 MB on its first request would keep the whole download waiting for it at the end.
        if (measured <= 0L) return minOf(maxChunk, PROBE_CHUNK_BYTES)
        // About four seconds of work per stream: long enough to amortise the request
        // round trip, short enough that a slow link can only delay the finish by a few
        // seconds. The measured speed is the link's total across its streams.
        val perStream = measured / streamsFor(link.id).coerceAtLeast(1)
        return (perStream * CHUNK_SECONDS / 1000).coerceIn(MIN_CHUNK_BYTES, maxChunk)
    }

    /** Runs [next] on one coroutine per stream until it produces null. */
    private suspend fun drain(
        channel: FileChannel,
        links: List<Link>,
        next: (Link) -> ByteRange?,
        failed: MutableCollection<ByteRange>,
        firstError: AtomicReference<Exception?>,
    ) = coroutineScope {
        links.flatMap { link -> List(streamsFor(link.id).coerceAtLeast(1)) { link } }.forEach { link ->
            launch(Dispatchers.IO) {
                worker(channel, link, next, failed, firstError)
            }
        }
    }

    /** One stream: take ranges from [next] until it runs out, then exit. */
    private suspend fun worker(
        channel: FileChannel,
        link: Link,
        next: (Link) -> ByteRange?,
        failed: MutableCollection<ByteRange>,
        firstError: AtomicReference<Exception?>,
        stop: AtomicBoolean? = null,
    ) {
        // One buffer per worker, reused for every range it downloads.
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (currentCoroutineContext().isActive) {
            // Finish the range already in hand, then leave with the main pass.
            if (stop?.get() == true) break
            // A cap can be reached while a file is running; stop that link's streams
            // instead of letting them wait for tomorrow's reset.
            if (!isLinkAllowed(link.id)) break
            val range = next(link) ?: break
            val counter = activeByLink.getOrPut(link.id) { AtomicInteger() }
            counter.incrementAndGet()
            try {
                chunk(channel, range, link, buffer)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed.add(range)
                firstError.compareAndSet(null, e)
                // Several ranges failing before a single byte arrives means the
                // server or every route is down. Fail now instead of grinding
                // through the whole file at up to 20 s per attempt.
                if (done.get() == 0L && failed.size >= ABORT_AFTER_FAILURES) {
                    throw IOException("Download cannot start: ${e.message}", e)
                }
            } finally {
                counter.decrementAndGet()
            }
        }
    }

    /** Grabs one byte range, moving to another link if this one keeps failing. */
    private suspend fun chunk(
        channel: FileChannel,
        range: ByteRange,
        preferred: Link,
        buffer: ByteArray,
    ) {
        var attempt = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val link = awaitLink(preferred) ?: throw IOException("Every link failed")
            try {
                fetch(link, range, channel, buffer)
                failures[link.id]?.set(0)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val count = failures.getOrPut(link.id) { AtomicInteger() }.incrementAndGet()
                if (count >= 3) deadLinks.add(link.id)
                attempt++
                if (attempt >= 6) {
                    throw IOException("Range ${range.start}-${range.end} failed: ${e.message}", e)
                }
                delay(300L * attempt)
            }
        }
    }

    private suspend fun fetch(
        link: Link,
        range: ByteRange,
        channel: FileChannel,
        buffer: ByteArray,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=${range.start}-${range.end}")
            .build()

        // Bytes accepted in this attempt. If the attempt dies halfway (flaky link),
        // the counters are rolled back so a retry does not double count progress.
        var thisAttempt = 0L

        try {
            clientFor(link).newCall(request).execute().use { response ->
                if (response.code != 206) throw IOException("Server answered ${response.code}")
                checkRange(response.header("Content-Range"), range.start, range.end)
                val body = response.body ?: throw IOException("Empty response body")
                val expected = range.end - range.start + 1
                var written = 0L
                var position = range.start

                body.byteStream().use { input ->
                    while (written < expected) {
                        currentCoroutineContext().ensureActive()
                        val want = minOf(buffer.size.toLong(), expected - written).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read < 0) break

                        var offset = 0
                        while (offset < read) {
                            val slice = ByteBuffer.wrap(buffer, offset, read - offset)
                            val wrote = channel.write(slice, position + offset)
                            if (wrote <= 0) throw IOException("Could not write to storage")
                            offset += wrote
                        }

                        position += read
                        written += read
                        thisAttempt += read
                        addBytes(link.id, read.toLong())
                        // Pace the actual bytes, not the requests: short reads from a
                        // sluggish server still count against the cap.
                        limiter.acquire(link.id, read)
                    }
                }

                if (written != expected) {
                    throw IOException(
                        "Short range ${range.start}-${range.end}: $written/$expected bytes",
                    )
                }
            }
        } catch (e: Throwable) {
            if (thisAttempt > 0) addBytes(link.id, -thisAttempt)
            throw e
        }
    }

    /**
     * A 206 is not enough: if the server answers with a different start offset, the
     * bytes would land in the wrong place and silently corrupt the file.
     */
    private fun checkRange(header: String?, start: Long, end: Long) {
        val value = header ?: throw IOException("Server sent no Content-Range")
        val match = Regex("bytes\\s+(\\d+)-(\\d+)/").find(value)
            ?: throw IOException("Unreadable Content-Range: $value")
        val gotStart = match.groupValues[1].toLongOrNull()
        if (gotStart != start) {
            throw IOException("Server sent range $value, asked $start-$end")
        }
    }

    /** Used when the server ignores Range requests or the size is unknown. */
    private suspend fun runSingle(part: File) = coroutineScope {
        publish(snap(DownloadStatus.RUNNING))
        val ticker = launch {
            while (isActive) {
                delay(500)
                sample()
                publish(snap(DownloadStatus.RUNNING))
            }
        }
        try {
            withContext(Dispatchers.IO) { streamWhole(part) }
        } finally {
            ticker.cancel()
        }
    }

    private suspend fun streamWhole(part: File) {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val link = awaitLink(null) ?: throw IOException("No link is connected")
            try {
                part.delete()
                done.set(0)
                perLinkBytes.values.forEach { it.set(0) }
                // A restart from byte zero must not inherit the old speed window either.
                history.clear()
                historyByLink.clear()
                perLinkSpeed.clear()
                val request = Request.Builder().url(url).build()

                clientFor(link).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Server answered ${response.code}")
                    totalBytes = response.body?.contentLength() ?: -1L
                    val body = response.body ?: throw IOException("Empty response body")
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                addBytes(link.id, read.toLong())
                                limiter.acquire(link.id, read)
                            }
                        }
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                attempt++
                if (attempt >= 4) throw IOException("Download failed: ${e.message}", e)
                delay(1000L * attempt)
            }
        }
    }

    /**
     * Waits up to 30 s for a link to come back. Mobile handovers (LTE -> 5G, Wi-Fi
     * roaming, a VPN reconnect) briefly tear the network down; failing the whole job
     * for that is how a download appears to "stop after a few seconds".
     */
    private suspend fun awaitLink(preferred: Link?): Link? {
        var waited = 0L
        val budget = if (autoRetry()) WAIT_FOR_LINK_MS else 0L
        while (waited <= budget) {
            pickLink(preferred)?.let {
                waitingForLink = false
                return it
            }
            waitingForLink = true
            delay(500)
            waited += 500
        }
        return null
    }

    private fun pickLink(preferred: Link?): Link? {
        val usable = usableLinks()
        if (usable.isEmpty()) return null
        val alive = usable.filter { it.id !in deadLinks }.ifEmpty {
            // Give links another chance rather than failing the whole download.
            deadLinks.clear()
            usable
        }
        if (preferred != null && preferred.usable && isLinkAllowed(preferred.id) &&
            allowedLinkIds?.contains(preferred.id) != false && alive.any { it.id == preferred.id }) {
            return preferred
        }
        return alive.minByOrNull { activeByLink[it.id]?.get() ?: 0 }
    }

    private fun clientFor(link: Link): OkHttpClient {
        val network = link.network() ?: throw IOException("${link.title} disconnected")
        val cached = clients[link.id]
        if (cached != null && cached.first == network) return cached.second
        return clientFor(network).also { clients[link.id] = network to it }
    }

    private fun addBytes(linkId: String, count: Long) {
        perLinkBytes.getOrPut(linkId) { AtomicLong() }.addAndGet(count)
        done.addAndGet(count)
    }

    private fun sample() {
        val now = System.currentTimeMillis()
        val perLink = perLinkBytes.mapValues { it.value.get() }

        record(history, now, done.get())
        speed = rateOf(history)

        perLink.forEach { (id, bytes) ->
            record(historyByLink.getOrPut(id) { ArrayDeque() }, now, bytes)
            perLinkSpeed[id] = rateOf(historyByLink.getValue(id))
        }
        // Forget links that are gone so a re-enabled link does not inherit old samples.
        historyByLink.keys.retainAll(perLink.keys)
        perLinkSpeed.keys.retainAll(perLink.keys)
    }

    private fun record(q: ArrayDeque<Pair<Long, Long>>, now: Long, bytes: Long) {
        q.addLast(now to bytes)
        while (q.size > 2 && now - q.first().first > SPEED_WINDOW_MS) q.removeFirst()
    }

    /** Bytes per second across the whole window, or 0 until there are two samples. */
    private fun rateOf(q: ArrayDeque<Pair<Long, Long>>): Long {
        if (q.size < 2) return 0L
        val elapsed = q.last().first - q.first().first
        if (elapsed <= 0L) return 0L
        return ((q.last().second - q.first().second) * 1000 / elapsed).coerceAtLeast(0L)
    }

    private fun snap(
        status: DownloadStatus,
        message: String? = null,
        savedTo: String? = null,
        savedUri: String? = null,
    ): DownloadSnapshot {
        val active = usableLinks().map { it.id }.toSet()
        val links = registry.links.mapNotNull { link ->
            val bytes = perLinkBytes[link.id]?.get() ?: 0L
            if (bytes == 0L && link.id !in active) {
                null
            } else {
                LinkProgress(
                    id = link.id,
                    bytes = bytes,
                    speedBps = perLinkSpeed[link.id] ?: 0L,
                    streams = activeByLink[link.id]?.get() ?: 0,
                )
            }
        }
        return DownloadSnapshot(
            name = name,
            url = url,
            status = status,
            totalBytes = totalBytes,
            bytesDone = done.get(),
            speedBps = speed,
            links = links,
            parallel = parallel,
            message = message ?: when {
                waitingForLink && registry.usable(allowedLinkIds).isEmpty() -> "Waiting for a network…"
                waitingForLink -> "Plexo's data limit is reached on every selected link"
                else -> null
            },
            savedTo = savedTo,
            savedUri = savedUri,
        )
    }

    private fun noLinksMessage(): String {
        val selected = registry.usable(allowedLinkIds)
        return if (selected.isEmpty()) {
            "No link is connected. Turn on Wi-Fi or mobile data."
        } else {
            "Plexo's data limit is reached on every selected link"
        }
    }

    private fun nameFrom(url: String, disposition: String?): String {
        if (disposition != null) {
            val encoded = Regex("filename\\*\\s*=\\s*UTF-8''([^;]+)", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.getOrNull(1)
            if (!encoded.isNullOrBlank()) return Uri.decode(encoded.trim())

            val plain = Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE)
                .find(disposition)?.groupValues?.getOrNull(1)
            if (!plain.isNullOrBlank()) return plain.trim()
        }
        val last = Uri.parse(url).lastPathSegment.orEmpty()
        return if (last.contains('.')) last else "download"
    }

    private class ByteRange(val start: Long, val end: Long)

    private companion object {
        /** Extra passes over ranges that failed, after the main pass. */
        const val MAX_RETRY_ROUNDS = 3
        /** Ranges that may fail before any byte arrives before the job is called dead. */
        const val ABORT_AFTER_FAILURES = 6
        /** Floor for one request: small enough that a 0.2 Mbps link cannot hold the tail. */
        const val MIN_CHUNK_BYTES = 256L * 1024
        const val MAX_CHUNK_BYTES = 128L * 1024 * 1024
        /** Aim for about four seconds of transfer per request. */
        const val CHUNK_SECONDS = 4L
        /** Fraction of the configured maximum used before a link has a measured speed. */
        const val PROBE_CHUNK_BYTES = 512L * 1024
        /** Read buffer per worker, reused across ranges. */
        const val READ_BUFFER_BYTES = 256 * 1024
        /** Speed is averaged over this window so the readout does not flicker. */
        const val SPEED_WINDOW_MS = 2000L
        /** How long workers wait for a dropped link before failing. */
        const val WAIT_FOR_LINK_MS = 30_000L
        /** How long streams for a selected but not-yet-connected link wait to join. */
        const val LATE_LINK_WAIT_MS = 120_000L
    }
}

/** One item in the download list, active or finished. */
data class DownloadEntry(
    val id: String,
    val snapshot: DownloadSnapshot,
    val requestedName: String? = null,
    val networkIds: Set<String>? = null,
    val streams: Int? = null,
    val history: List<Long> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
) {
    val isActive: Boolean get() = snapshot.status.active
    val isQueued: Boolean get() = snapshot.status == DownloadStatus.QUEUED
    val isFinished: Boolean get() = snapshot.status.finished
}

/**
 * The app-wide download list: a queue that runs up to the configured number of
 * files at once, a history of finished files, and the shared speed/data policy.
 * The foreground service and the UI both observe [entries].
 */
class DownloadRepository(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = app.getSharedPreferences("plexo_downloads", Context.MODE_PRIVATE)

    private val _entries = MutableStateFlow(loadHistory())
    val entries: StateFlow<List<DownloadEntry>> = _entries.asStateFlow()

    val links = LinkRegistry(app)
    val settings = Settings(app)

    private val limiter = SharedRateLimiter(
        totalLimit = { settings.effectiveLimitBytes },
        linkLimit = { id -> settings.linkSpeedLimitMbps(id).toLong() * 1024L * 1024L },
    )

    private val jobs = ConcurrentHashMap<String, Job>()
    private val pumpLock = Any()
    private val lastSeenBytes = HashMap<String, MutableMap<String, Long>>()
    private val settled = HashSet<String>()

    init {
        deleteOrphanParts()
        scope.launch { sampleLoop() }
        pump()
    }

    val activeCount: Int get() = jobs.size
    val busy: Boolean get() = jobs.isNotEmpty()

    val totalSpeed: Long
        get() = _entries.value.filter { it.isActive }.sumOf { it.snapshot.speedBps }

    fun start(
        url: String,
        name: String? = null,
        networkIds: Set<String>? = null,
        streams: Int? = null,
    ): String {
        val id = UUID.randomUUID().toString()
        val entry = DownloadEntry(
            id = id,
            snapshot = DownloadSnapshot.initial(url, name),
            requestedName = name,
            networkIds = networkIds?.takeIf { it.isNotEmpty() },
            streams = streams,
        )
        _entries.update { it + entry }
        pump()
        return id
    }

    private fun pump() = synchronized(pumpLock) {
        val schedule = settings.schedule
        val open = schedule.isOpen()
        if (!open) {
            _entries.update { list ->
                list.map {
                    if (it.isQueued && it.snapshot.message != WAIT_SCHEDULE) {
                        it.copy(snapshot = it.snapshot.copy(message = WAIT_SCHEDULE))
                    } else {
                        it
                    }
                }
            }
            return
        }
        _entries.update { list ->
            list.map {
                if (it.isQueued && it.snapshot.message == WAIT_SCHEDULE) {
                    it.copy(snapshot = it.snapshot.copy(message = "Queued"))
                } else {
                    it
                }
            }
        }

        var slots = settings.downloadsAtOnce - jobs.size
        if (slots <= 0) return
        val queued = _entries.value
            .filter { it.isQueued }
            .sortedBy { it.createdAt }
        for (entry in queued) {
            if (slots <= 0) break
            launch(entry)
            slots--
        }
    }

    private fun launch(entry: DownloadEntry) {
        update(entry.id) {
            it.copy(
                snapshot = it.snapshot.copy(
                    status = DownloadStatus.PREPARING,
                    message = null,
                    speedBps = 0,
                ),
                history = emptyList(),
            )
        }
        lastSeenBytes[entry.id] = HashMap()
        settled.remove(entry.id)

        val task = DownloadTask(
            ctx = app,
            registry = links,
            id = entry.id,
            url = entry.snapshot.url,
            requestedName = entry.requestedName,
            streamsFor = { linkId ->
                entry.streams ?: settings.linkStreams(linkId, settings.streamsPerLink)
            },
            chunkSize = settings.chunkMb.toLong() * 1024L * 1024L,
            allowedLinkIds = entry.networkIds,
            limiter = limiter,
            isLinkAllowed = { linkId -> !settings.linkDataBlocked(linkId) },
            autoRetry = { settings.autoRetry },
            publish = { snapshot ->
                update(entry.id) { it.copy(snapshot = snapshot) }
                if (snapshot.status.finished) persistHistory()
            },
        )

        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { task.run() }
        jobs[entry.id] = job
        job.invokeOnCompletion {
            jobs.remove(entry.id)
            pump()
        }
        job.start()
    }

    fun cancel(id: String) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        val job = jobs[id]
        if (job != null) {
            job.cancel()
        } else if (entry.isQueued) {
            update(id) {
                it.copy(snapshot = it.snapshot.copy(status = DownloadStatus.CANCELLED, message = "Cancelled"))
            }
        }
    }

    fun cancelAll() {
        jobs.keys.toList().forEach { jobs[it]?.cancel() }
        _entries.update { list ->
            list.map {
                if (it.isQueued) it.copy(snapshot = it.snapshot.copy(status = DownloadStatus.CANCELLED, message = "Cancelled")) else it
            }
        }
    }

    fun retry(id: String) {
        if (jobs.containsKey(id)) return
        update(id) {
            it.copy(
                snapshot = DownloadSnapshot.initial(it.snapshot.url, it.requestedName),
                history = emptyList(),
                createdAt = System.currentTimeMillis(),
            )
        }
        lastSeenBytes.remove(id)
        settled.remove(id)
        pump()
    }

    /** Desktop's "Download again": a fresh queue entry, leaving the finished one intact. */
    fun again(id: String) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        start(
            url = entry.snapshot.url,
            name = entry.requestedName,
            networkIds = entry.networkIds,
            streams = entry.streams,
        )
    }

    fun remove(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        lastSeenBytes.remove(id)
        settled.remove(id)
        _entries.update { list -> list.filterNot { it.id == id } }
        persistHistory()
        pump()
    }

    fun clearFinished() {
        _entries.update { list -> list.filterNot { it.isFinished } }
        persistHistory()
    }

    /** Kept for the foreground service's generic cancel action. */
    fun cancel() = cancelAll()

    private fun update(id: String, transform: (DownloadEntry) -> DownloadEntry) {
        _entries.update { list ->
            list.map { if (it.id == id) transform(it) else it }
        }
    }

    // --- sampling: graph history, per-network data accounting, schedule wake-up

    private suspend fun sampleLoop() {
        var tick = 0
        while (scope.isActive) {
            delay(1000)
            tick++
            val list = _entries.value
            for (entry in list) {
                if (entry.isActive) {
                    update(entry.id) {
                        it.copy(history = (it.history + it.snapshot.speedBps).takeLast(60))
                    }
                }
                if (!entry.isActive && entry.id in settled) continue

                val seen = lastSeenBytes.getOrPut(entry.id) { HashMap() }
                entry.snapshot.links.forEach { progress ->
                    val previous = seen[progress.id] ?: 0L
                    if (progress.bytes > previous) {
                        val period = settings.linkDataPeriod(progress.id)
                        settings.addLinkDataUsed(progress.id, period, progress.bytes - previous)
                    }
                    seen[progress.id] = progress.bytes
                }
                if (entry.isFinished) settled.add(entry.id)
            }
            if (tick % 5 == 0) pump()
        }
    }

    // --- finished history ---------------------------------------------------

    private fun persistHistory() {
        val finished = _entries.value
            .filter { it.snapshot.status == DownloadStatus.DONE || it.snapshot.status == DownloadStatus.ERROR }
            .takeLast(100)
        val array = org.json.JSONArray()
        finished.forEach { entry ->
            val o = org.json.JSONObject()
            o.put("id", entry.id)
            o.put("name", entry.snapshot.name)
            o.put("url", entry.snapshot.url)
            o.put("status", entry.snapshot.status.name)
            o.put("total", entry.snapshot.totalBytes)
            o.put("done", entry.snapshot.bytesDone)
            o.put("savedTo", entry.snapshot.savedTo)
            o.put("savedUri", entry.snapshot.savedUri)
            o.put("message", entry.snapshot.message)
            o.put("createdAt", entry.createdAt)
            array.put(o)
        }
        prefs.edit().putString("history", array.toString()).apply()
    }

    private fun loadHistory(): List<DownloadEntry> {
        val raw = prefs.getString("history", null) ?: return emptyList()
        return runCatching {
            val array = org.json.JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val o = array.getJSONObject(index)
                val status = runCatching {
                    DownloadStatus.valueOf(o.getString("status"))
                }.getOrDefault(DownloadStatus.DONE)
                DownloadEntry(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    snapshot = DownloadSnapshot(
                        name = o.optString("name", "download"),
                        url = o.optString("url"),
                        status = status,
                        totalBytes = o.optLong("total"),
                        bytesDone = o.optLong("done"),
                        speedBps = 0,
                        links = emptyList(),
                        parallel = false,
                        message = o.optString("message").takeIf { it.isNotBlank() },
                        savedTo = o.optString("savedTo").takeIf { it.isNotBlank() },
                        savedUri = o.optString("savedUri").takeIf { it.isNotBlank() },
                    ),
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            }.filter { it.snapshot.status == DownloadStatus.DONE || it.snapshot.status == DownloadStatus.ERROR }
        }.getOrDefault(emptyList())
    }

    private fun deleteOrphanParts() {
        runCatching {
            File(app.filesDir, "downloads").listFiles()
                ?.filter { it.isFile }
                ?.forEach { it.delete() }
        }
    }

    private companion object {
        const val WAIT_SCHEDULE = "Waiting for schedule"
    }
}
