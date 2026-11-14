package app.getplexo.mobile.core

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

/**
 * One shared pacer for every download and every stream. Desktop's total speed
 * limit belongs to the app, not to a socket: throttling each request separately
 * would multiply the cap by the stream count and overshoot badly.
 *
 * A worker reserves its transmission slot under a lock, then sleeps until that
 * slot. A total limit uses one global line; a per-network cap gets a second line
 * and the worker waits for whichever is later, so both limits are obeyed.
 */
class SharedRateLimiter(
    private val totalLimit: () -> Long,
    private val linkLimit: (String) -> Long,
) {
    private val lock = Any()
    private var globalFree = 0L
    private val linkFree = ConcurrentHashMap<String, Long>()

    suspend fun acquire(linkId: String, bytes: Int) {
        if (bytes <= 0) return
        val total = totalLimit()
        val perLink = linkLimit(linkId)
        if (total <= 0L && perLink <= 0L) return

        val waitNanos = synchronized(lock) {
            val now = System.nanoTime()
            var wait = 0L
            if (total > 0L) {
                val start = maxOf(now, globalFree)
                globalFree = start + bytes * 1_000_000_000L / total
                wait = maxOf(wait, start - now)
            }
            if (perLink > 0L) {
                val key = "$linkId\u0000$perLink"
                val start = maxOf(now, linkFree[key] ?: now)
                linkFree[key] = start + bytes * 1_000_000_000L / perLink
                wait = maxOf(wait, start - now)
            }
            wait
        }
        if (waitNanos > 0) delay((waitNanos / 1_000_000L).coerceAtLeast(1L))
    }
}
