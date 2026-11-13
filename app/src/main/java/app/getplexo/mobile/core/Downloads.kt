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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

enum class DownloadStatus {
    PREPARING,
    RUNNING,
    SAVING,
    DONE,
    ERROR,
    CANCELLED;

    val finished: Boolean get() = this == DONE || this == ERROR || this == CANCELLED
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
        fun initial(url: String, name: String?) = DownloadSnapshot(
            name = name ?: "download",
            url = url,
            status = DownloadStatus.PREPARING,
            totalBytes = 0,
            bytesDone = 0,
            speedBps = 0,
            links = emptyList(),
            parallel = false,
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
    private val url: String,
    private val requestedName: String?,
    private val streamsPerLink: Int,
    private val chunkSize: Long,
    private val publish: (DownloadSnapshot) -> Unit,
) {
    private val id = UUID.randomUUID().toString()

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
    private var lastSampleAt = 0L
    private var lastTotal = 0L
    private val lastLinkSample = HashMap<String, Long>()

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
            val where = withContext(Dispatchers.IO) { Store.save(ctx, part, name, mime) }
            part.delete()
            publish(snap(DownloadStatus.DONE, savedTo = where))
        } catch (e: CancellationException) {
            publish(snap(DownloadStatus.CANCELLED, message = "Cancelled"))
            runCatching { part.delete() }
            throw e
        } catch (e: Exception) {
            publish(snap(DownloadStatus.ERROR, message = e.message ?: e.javaClass.simpleName))
            runCatching { part.delete() }
        }
    }

    private fun partFile(): File =
        File(File(ctx.filesDir, "downloads").apply { mkdirs() }, "$id.part")

    /** One ranged request tells us size, range support and the real file name. */
    private suspend fun probe() = withContext(Dispatchers.IO) {
        val link = registry.usable().firstOrNull()
            ?: throw IOException("No link is connected. Turn on Wi-Fi or mobile data.")
        mime = null
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .get()
            .build()

        clientFor(link.network()!!).newCall(request).execute().use { response ->
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
        val chunkCount = ((totalBytes + chunkSize - 1) / chunkSize).toInt().coerceAtLeast(1)
        val cursor = AtomicInteger(0)
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
            val usable = registry.usable()
            if (usable.isEmpty()) throw IOException("No link is connected")

            usable.flatMap { link -> List(streamsPerLink) { link } }.forEach { link ->
                launch(Dispatchers.IO) {
                    while (isActive) {
                        val index = cursor.getAndIncrement()
                        if (index >= chunkCount) break
                        val counter = activeByLink.getOrPut(link.id) { AtomicInteger() }
                        counter.incrementAndGet()
                        try {
                            chunk(channel, index, link)
                        } finally {
                            counter.decrementAndGet()
                        }
                    }
                }
            }
        } finally {
            ticker.cancel()
            channel.close()
        }
    }

    /** Grabs one byte range, moving to another link if this one keeps failing. */
    private suspend fun chunk(channel: FileChannel, index: Int, preferred: Link) {
        val start = index.toLong() * chunkSize
        val end = minOf(totalBytes - 1, start + chunkSize - 1)
        var attempt = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            val link = pickLink(preferred) ?: throw IOException("Every link failed")
            try {
                fetch(link, start, end, channel, index)
                failures[link.id]?.set(0)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val count = failures.getOrPut(link.id) { AtomicInteger() }.incrementAndGet()
                if (count >= 3) deadLinks.add(link.id)
                attempt++
                if (attempt >= 6) throw IOException("Chunk $index failed: ${e.message}", e)
                delay(300L * attempt)
            }
        }
    }

    private suspend fun fetch(
        link: Link,
        start: Long,
        end: Long,
        channel: FileChannel,
        index: Int,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$start-$end")
            .build()

        // Bytes accepted in this attempt. If the attempt dies halfway (flaky link),
        // the counters are rolled back so a retry does not double count progress.
        var thisAttempt = 0L

        try {
            clientFor(link).newCall(request).execute().use { response ->
                if (response.code != 206) throw IOException("Server answered ${response.code}")
                val body = response.body ?: throw IOException("Empty response body")
                val expected = end - start + 1
                var written = 0L
                var position = start

                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
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
                    }
                }

                if (written != expected) {
                    throw IOException("Short chunk $index: $written/$expected bytes")
                }
            }
        } catch (e: Throwable) {
            if (thisAttempt > 0) addBytes(link.id, -thisAttempt)
            throw e
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
            val link = pickLink(null) ?: throw IOException("No link is connected")
            try {
                part.delete()
                done.set(0)
                perLinkBytes.values.forEach { it.set(0) }
                val request = Request.Builder().url(url).build()

                clientFor(link).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Server answered ${response.code}")
                    totalBytes = response.body?.contentLength() ?: -1L
                    val body = response.body ?: throw IOException("Empty response body")
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                out.write(buffer, 0, read)
                                addBytes(link.id, read.toLong())
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

    private fun pickLink(preferred: Link?): Link? {
        val usable = registry.usable()
        if (usable.isEmpty()) return null
        val alive = usable.filter { it.id !in deadLinks }.ifEmpty {
            // Give links another chance rather than failing the whole download.
            deadLinks.clear()
            usable
        }
        if (preferred != null && preferred.usable && alive.any { it.id == preferred.id }) {
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
        val current = done.get()
        val perLink = perLinkBytes.mapValues { it.value.get() }

        if (lastSampleAt == 0L) {
            lastSampleAt = now
            lastTotal = current
            lastLinkSample.putAll(perLink)
            return
        }

        val elapsed = (now - lastSampleAt).coerceAtLeast(1)
        speed = ((current - lastTotal) * 1000 / elapsed).coerceAtLeast(0)
        perLink.forEach { (id, bytes) ->
            val before = lastLinkSample[id] ?: 0L
            perLinkSpeed[id] = (((bytes - before) * 1000) / elapsed).coerceAtLeast(0)
        }
        lastSampleAt = now
        lastTotal = current
        lastLinkSample.putAll(perLink)
    }

    private fun snap(
        status: DownloadStatus,
        message: String? = null,
        savedTo: String? = null,
    ): DownloadSnapshot {
        val active = registry.usable().map { it.id }.toSet()
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
            message = message,
            savedTo = savedTo,
        )
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
}

/** Single app-wide download state. The UI and the foreground service both observe it. */
class DownloadRepository(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<DownloadSnapshot?>(null)
    val state: StateFlow<DownloadSnapshot?> = _state.asStateFlow()

    val links = LinkRegistry(app)

    private var job: Job? = null

    val busy: Boolean get() = job?.isActive == true

    fun start(url: String, name: String?, streams: Int, chunkSize: Long) {
        if (busy) return
        // Publish synchronously so the UI and the foreground service never see the
        // previous download's result after a new one has been requested.
        _state.value = DownloadSnapshot.initial(url, name)
        val task = DownloadTask(app, links, url, name, streams, chunkSize) { _state.value = it }
        job = scope.launch { task.run() }
    }

    fun cancel() {
        job?.cancel()
    }

    fun dismiss() {
        if (!busy) _state.value = null
    }
}
