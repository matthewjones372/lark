package io.github.matthewjones372.lark

import kotlin.math.log10
import kotlin.math.max

/**
 * TCP Vegas, as a concurrency limit (spec 0171). The fastest window seen is the dependency unloaded; a window
 * slower than that means about `limit * (1 - unloaded / took)` calls are queueing inside it. Windows rather than
 * single calls on both sides, since the fastest single call is luck, and comparing with it reads a dependency's
 * ordinary jitter as queueing, more so the higher the limit.
 *
 * The limit moves once per window of about `limit` calls, on the window's average, since one call's time is
 * noise. With no queue it grows by a tenth; with a short one by `log10(limit)`; with a long one it drops at once
 * to what the dependency is taking, `limit * unloaded / took`, plus a short queue, so a dependency that loses
 * capacity is relieved within a window rather than a step at a time. A failed call backs it off at once.
 */
internal class Vegas(initial: Int, private val min: Int, private val max: Int) {
    private var limit = initial.toDouble()
    private var unloaded = Double.MAX_VALUE
    private var windows = 0L

    private var windowCalls = 0
    private var windowTook = 0L
    private var windowMostInside = 0

    /** The limit after one call that took [tookNanos] with [inFlight] calls inside, itself among them. */
    fun sample(tookNanos: Long, inFlight: Int, dropped: Boolean): Int {
        if (dropped) {
            limit = max(min.toDouble(), limit * BACKOFF)
            windowCalls = 0
            return limit.toInt()
        }
        windowCalls++
        windowTook += max(1L, tookNanos)
        windowMostInside = maxOf(windowMostInside, inFlight)
        if (windowCalls < max(MIN_WINDOW.toDouble(), limit)) return limit.toInt()

        val average = windowTook.toDouble() / windowCalls
        // Forget the fastest window now and then, so a dependency that got slower for good is not read as queueing
        // for ever.
        if (++windows % FORGET_EVERY == 0L) unloaded = average
        unloaded = minOf(unloaded, average)
        val busy = windowMostInside >= limit / 2
        windowCalls = 0
        windowTook = 0
        windowMostInside = 0

        val queued = limit * (1 - unloaded / average)
        val step = max(1.0, log10(limit))
        limit = when {
            // Only a limit that is being used grows: an idle one would otherwise climb to max on quiet calls.
            queued < 1 && busy -> limit + max(step, limit * GROWTH)

            queued <= GROW_BELOW * step && busy -> limit + step

            queued >= SHRINK_ABOVE * step -> max(limit * unloaded / average + GROW_BELOW * step, limit * MOST_CUT)

            else -> limit
        }.coerceIn(min.toDouble(), max.toDouble())
        return limit.toInt()
    }

    private companion object {
        const val BACKOFF = 0.9
        const val GROWTH = 0.1
        const val MOST_CUT = 0.5
        const val GROW_BELOW = 3
        const val SHRINK_ABOVE = 6
        const val MIN_WINDOW = 16
        const val FORGET_EVERY = 100L
    }
}
