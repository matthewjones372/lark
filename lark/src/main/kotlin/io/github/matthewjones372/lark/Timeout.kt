package io.github.matthewjones372.lark

import arrow.core.raise.Raise
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

/**
 * Races [block] against a sleeper of [duration] and interrupts whichever loses, so a block that does not
 * answer in time ends at its next interruptible call and this throws.
 */
fun <E, A> Raise<E>.timeout(duration: Duration, block: Raise<E>.() -> A): A =
    raceN(block) { duration.sleepOff() }
        .fold({ it }, { throw TimeoutException("no answer within $duration") })

/** The same race, answering with null rather than throwing when the sleeper wins. */
fun <E, A> Raise<E>.timeoutOrNull(duration: Duration, block: Raise<E>.() -> A): A? =
    raceN(block) { duration.sleepOff() }.fold({ it }, { null })

/** The same, for code outside any `Raise`. */
fun <A> timeout(duration: Duration, block: () -> A): A = Unraisable.timeout(duration) { block() }

/** The same, for code outside any `Raise`. */
fun <A> timeoutOrNull(duration: Duration, block: () -> A): A? = Unraisable.timeoutOrNull(duration) { block() }
