package io.github.matthewjones372.lark

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * Caps how many callers are inside one dependency at once (spec 0112). A caller that finds every permit taken waits
 * up to [maxWait] for one, and is refused after. It is called through a [Policy].
 */
class Bulkhead(val name: String, maxConcurrent: Int, internal val maxWait: Duration = ZERO) {
    init {
        require(maxConcurrent >= 1) { "Bulkhead \"$name\" must let at least one caller in, not $maxConcurrent." }
    }

    // Fair, so the longest-waiting caller is never overtaken.
    private val permits = Semaphore(maxConcurrent, true)

    /** Permits free now. */
    val available: Int get() = permits.availablePermits()

    // The timed tryAcquire even for no wait: the untimed one barges past a fair semaphore's queue.
    internal fun acquire(wait: Duration): Boolean =
        permits.tryAcquire(wait.coerceAtLeast(ZERO).inWholeNanoseconds, TimeUnit.NANOSECONDS)

    internal fun release() = permits.release()
}
