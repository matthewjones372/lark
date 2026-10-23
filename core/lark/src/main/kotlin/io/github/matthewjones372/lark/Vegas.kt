package io.github.matthewjones372.lark

import kotlin.math.log10
import kotlin.math.max

/**
 * TCP Vegas, as a concurrency limit (spec 0171). The fastest call seen is the dependency unloaded; a slower one
 * means calls are queueing inside it, about `limit * (1 - unloaded / took)` of them. A short queue grows the
 * limit, a long one shrinks it, both by `log10(limit)`, so it settles a little above what the dependency can
 * take at once and follows that level when it moves. A failed call backs off at once.
 */
internal class Vegas(initial: Int, private val min: Int, private val max: Int) {
    private var limit = initial.toDouble()
    private var unloaded = Long.MAX_VALUE
    private var samples = 0L

    /** The limit after one call that took [tookNanos] with [inFlight] calls inside, itself among them. */
    fun sample(tookNanos: Long, inFlight: Int, dropped: Boolean): Int {
        if (dropped) {
            limit = max(min.toDouble(), limit * BACKOFF)
            return limit.toInt()
        }
        // Forget the fastest call now and then, so a dependency that got slower for good is not read as queueing
        // for ever.
        if (++samples % FORGET_EVERY == 0L) unloaded = tookNanos
        unloaded = minOf(unloaded, max(1L, tookNanos))
        val queued = limit * (1 - unloaded.toDouble() / max(1L, tookNanos))
        val step = max(1.0, log10(limit))
        limit = when {
            // Only a limit that is being used grows: an idle one would otherwise climb to max on quiet calls.
            queued <= GROW_BELOW * step && inFlight >= limit / 2 -> limit + step

            queued >= SHRINK_ABOVE * step -> limit - step

            else -> limit
        }.coerceIn(min.toDouble(), max.toDouble())
        return limit.toInt()
    }

    private companion object {
        const val BACKOFF = 0.9
        const val GROW_BELOW = 3
        const val SHRINK_ABOVE = 6
        const val FORGET_EVERY = 10_000L
    }
}
