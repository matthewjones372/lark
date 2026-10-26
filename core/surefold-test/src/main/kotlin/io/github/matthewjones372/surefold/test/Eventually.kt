package io.github.matthewjones372.surefold.test

import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.SurefoldLocal
import io.github.matthewjones372.surefold.retryOrElse
import io.github.matthewjones372.surefold.surefoldLocal
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs [block] again every [every] until it returns a value that holds, and once [within] has passed on the inherited
 * clock gives up. A try that throws, or that returns a [Verdict] that did not hold, is not yet. Giving up on a throw
 * throws [GaveUp] with it; giving up on a [Verdict], returned or thrown, answers its [Verdict.gaveUp]. Each try runs
 * through the bound [tries]. Blocking, so it works inside `use` and `testApp`, where `suspend` cannot.
 */
@OptIn(TrySeam::class)
fun <A> eventually(within: Duration, every: Duration = 20.milliseconds, block: () -> A): A =
    Schedule.spaced<Throwable>(every)
        .and(Schedule.upTo(within)) { retried, elapsed -> Waited(retried + 1, elapsed) }
        .retryOrElse({
            tries.get().attempt(block).also {
                if (it is Verdict &&
                    !it.held
                ) throw NotYet(it)
            }
        }) { last, w ->
            when (last) {
                is NotYet -> last.verdict.gaveUpAs(w)
                is Verdict -> last.gaveUpAs(w)
                else -> throw GaveUp(w.tries, w.elapsed, last)
            }
        }

/** How [eventually] runs each of its tries; a test engine binds one to keep a try that did not hold out of its test. */
@TrySeam
interface Tries {

    /** Runs one try of [block]; throwing, or answering a [Verdict] that did not hold, makes it not yet. */
    fun <A> attempt(block: () -> A): A
}

/** The [Tries] [eventually] runs its tries through; unbound, each try is just [block]. */
@TrySeam
val tries: SurefoldLocal<Tries> = surefoldLocal { Plainly }

/** A seam for surefold's own modules that keep a record of each try, such as a test engine. */
@RequiresOptIn("A seam for surefold's own modules that keep a record of each try.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
annotation class TrySeam

@OptIn(TrySeam::class)
private object Plainly : Tries {
    override fun <A> attempt(block: () -> A): A = block()
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
