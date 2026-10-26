package io.github.matthewjones372.surefold.test

import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.retryOrElse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs [block] again every [every] until it returns a value that holds, and once [within] has passed on the inherited
 * clock gives up. A try that throws, or that returns a [Verdict] that did not hold, is not yet. Giving up on a throw
 * throws [GaveUp] with it; giving up on a [Verdict] answers its [Verdict.gaveUp]. Blocking, so it works inside `use`
 * and `testApp`, where `suspend` cannot.
 */
fun <A> eventually(within: Duration, every: Duration = 20.milliseconds, block: () -> A): A =
    Schedule.spaced<Throwable>(every)
        .and(Schedule.upTo(within)) { retried, elapsed -> Waited(retried + 1, elapsed) }
        .retryOrElse({ block().also { if (it is Verdict && !it.held) throw NotYet(it) } }) { last, waited ->
            when (last) {
                is NotYet -> last.verdict.gaveUpAs(waited)
                else -> throw GaveUp(waited.tries, waited.elapsed, last)
            }
        }

/**
 * A result that can say it did not hold, such as a test's checks: [eventually] tries again until it holds, and when
 * time runs out answers [gaveUp] of the last one. [gaveUp] answers the same type as its receiver.
 */
interface Verdict {

    /** Whether this holds; [eventually] stops trying once it does. */
    val held: Boolean

    /** This, marked as given up after [tries] tries in [elapsed]. */
    fun gaveUp(tries: Long, elapsed: Duration): Verdict
}

/** An [eventually] that ran out of time after [tries] tries in [elapsed]; its cause is what the last try threw. */
class GaveUp(val tries: Long, val elapsed: Duration, last: Throwable) :
    AssertionError("gave up after $tries tries in $elapsed: ${last.message ?: last}", last)

private data class Waited(val tries: Long, val elapsed: Duration)

/** A try whose [verdict] did not hold, thrown only to ask the schedule for another. */
private class NotYet(val verdict: Verdict) : RuntimeException(null, null, false, false)

@Suppress("UNCHECKED_CAST") // a Verdict's gaveUp answers its own type, and the try answered an A
private fun <A> Verdict.gaveUpAs(waited: Waited): A = gaveUp(waited.tries, waited.elapsed) as A
