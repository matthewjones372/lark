package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.Schedule
import java.time.Instant
import kotlin.time.Duration

// The pulls with time in them, on a test's clock. Each waits by parking on the run's [Turns], so the clock
// stops at the instant it waits for, and the pull goes on when the test moves the clock there.

private fun Instant.plus(duration: Duration): Instant = plusNanos(duration.inWholeNanoseconds)

/**
 * The first element [Node.Tick.after] from the first pull, then one every [Node.Tick.every]. A tick that
 * falls due while nothing is asking is dropped, as Pekko's is: the next pull waits for the next one.
 */
internal fun Node.Tick.tick(turns: Turns): Pull {
    var due: Instant? = null
    return Pull {
        val now = turns.now()
        val next = generateSequence(due ?: now.plus(after)) { it.plus(every) }.first { it >= now }
        turns.park(until = next)
        if (turns.stopped) {
            null
        } else {
            due = next.plus(every)
            element
        }
    }
}

/** What one worker hands another: an element, or how the stream it pulls from ended, and taken once. */
private class Handoff {
    sealed interface Ended {
        data object Done : Ended

        class Threw(val thrown: Throwable) : Ended
    }

    var held: Any? = null

    fun take(): Any? = held.also { held = null }
}

/** [up] pulled on a worker of its own, one element at a time into [into], taken before the next is pulled. */
// The catch is as wide as a pipeline: whatever upstream threw is the consumer's to throw, on its own worker.
@Suppress("TooGenericExceptionCaught")
private fun Turns.feed(up: Pull, into: Handoff) = fork {
    try {
        while (!stopped) {
            val next = up.next()
            into.held = next ?: Handoff.Ended.Done
            if (next == null) break
            park { into.held == null }
        }
    } catch (thrown: Throwable) {
        into.held = Handoff.Ended.Threw(thrown)
    }
}

/**
 * Upstream is pulled by a worker of its own, so that a window can close while an element is still to come.
 * Windows follow each other every [Node.GroupedWithin.within] from the first pull, and a full group starts
 * the next window from the instant it was emitted, as Pekko's do. A window that closes empty emits nothing.
 */
internal fun Node.GroupedWithin.groupedWithin(turns: Turns): Pull =
    Windows(n, within, turns, Handoff().also { turns.feed(upstream.pull(), it) })

private class Windows(val n: Int, val within: Duration, val turns: Turns, val handoff: Handoff) : Pull {

    private var closes: Instant? = null
    private var ended = false

    override fun next(): Any? {
        val group = ArrayList<Any>(n)
        var emit: List<Any>? = null
        while (emit == null && !ended && !turns.stopped) emit = fill(group)
        return (emit ?: group).takeIf { it.isNotEmpty() }
    }

    /** One step of filling [group]: the group to emit, if this step ends it. */
    private fun fill(group: MutableList<Any>): List<Any>? {
        val now = turns.now()
        val window = closes ?: now.plus(within).also { closes = it }
        return when (val held = handoff.take()) {
            Handoff.Ended.Done -> group.also { ended = true }
            is Handoff.Ended.Threw -> throw held.thrown
            null -> closeOrWait(group, window, now)
            else -> group.apply { add(held) }.takeIf { it.size == n }?.also { closes = now.plus(within) }
        }
    }

    private fun closeOrWait(group: List<Any>, window: Instant, now: Instant): List<Any>? =
        if (window <= now) {
            closes = generateSequence(window) { it.plus(within) }.first { it > now }
            group.takeIf { it.isNotEmpty() }
        } else {
            turns.park(until = window) { handoff.held != null }
            null
        }
}

/**
 * Upstream again, from its first element, after each defect the schedule continues on, and after the delay
 * it asks for on the test's clock. A declared failure is not a defect, and passes through as it is.
 */
// The catch is as wide as a pipeline, because a defect is whatever upstream threw that it did not declare.
@Suppress("TooGenericExceptionCaught")
internal fun Node.RestartOnDefect.restarting(turns: Turns): Pull {
    var current = upstream.pull()
    var schedule = step
    return Pull {
        var element: Any? = null
        var over = false
        while (!over) {
            try {
                element = current.next()
                over = true
            } catch (failure: DeclaredFailure) {
                throw failure
            } catch (defect: Throwable) {
                when (val decision = schedule(defect)) {
                    is Schedule.Decision.Continue -> {
                        logger.log(
                            LogLine(
                                LogLevel.Warn,
                                "lark-stream: restarting in ${decision.delay} after $defect",
                                turns.now(),
                                defect,
                            ),
                        )
                        schedule = decision.step
                        turns.park(until = turns.now().plus(decision.delay))
                        current = upstream.pull()
                    }

                    is Schedule.Decision.Done -> throw defect
                }
            }
        }
        element
    }
}
