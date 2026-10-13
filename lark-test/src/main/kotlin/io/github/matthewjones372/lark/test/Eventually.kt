package io.github.matthewjones372.lark.test

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.retryOrElse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Runs [block] again every [every] until it returns, and once [within] has passed on the inherited clock throws
 * [GaveUp] with the last failure. Blocking, so it works inside `use` and `testApp`, where `suspend` cannot.
 */
fun <A> eventually(within: Duration, every: Duration = 20.milliseconds, block: () -> A): A =
    Schedule.spaced<Throwable>(every)
        .and(Schedule.upTo(within)) { retried, elapsed -> Waited(retried + 1, elapsed) }
        .retryOrElse(block) { last, waited -> throw GaveUp(waited.tries, waited.elapsed, last) }

/** An [eventually] that ran out of time after [tries] tries in [elapsed]; its cause is what the last try threw. */
class GaveUp(val tries: Long, val elapsed: Duration, last: Throwable) :
    AssertionError("gave up after $tries tries in $elapsed: ${last.message ?: last}", last)

private data class Waited(val tries: Long, val elapsed: Duration)
