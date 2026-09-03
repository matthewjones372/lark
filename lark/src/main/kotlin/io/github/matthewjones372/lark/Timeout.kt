package io.github.matthewjones372.lark

import arrow.core.raise.Raise
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

/**
 * Races [block] against a sleeper of [duration] and interrupts whichever loses, so a block that does not
 * answer in time ends at its next interruptible call and this throws. The sleeper is a fork like the block,
 * and runs where it does.
 */
fun <E, A> Raise<E>.timeout(duration: Duration, block: Raise<E>.() -> A): A = timeout(VirtualThreads, duration, block)

/** The same, with every fork run on [on]. */
fun <E, A> Raise<E>.timeout(on: Executor, duration: Duration, block: Raise<E>.() -> A): A =
    raceN(on, block) { duration.sleepOff() }
        .fold({ it }, { throw TimeoutException("no answer within $duration") })

/** The same race, answering with null rather than throwing when the sleeper wins. */
fun <E, A> Raise<E>.timeoutOrNull(duration: Duration, block: Raise<E>.() -> A): A? =
    timeoutOrNull(VirtualThreads, duration, block)

/** The same, with every fork run on [on]. */
fun <E, A> Raise<E>.timeoutOrNull(on: Executor, duration: Duration, block: Raise<E>.() -> A): A? =
    raceN(on, block) { duration.sleepOff() }.fold({ it }, { null })

/** The same, for code outside any `Raise`. */
fun <A> timeout(duration: Duration, block: () -> A): A = timeout(VirtualThreads, duration, block)

/** The same, with every fork run on [on]. */
fun <A> timeout(on: Executor, duration: Duration, block: () -> A): A =
    Unraisable.timeout(on, duration) { block() }

/** The same, for code outside any `Raise`. */
fun <A> timeoutOrNull(duration: Duration, block: () -> A): A? = timeoutOrNull(VirtualThreads, duration, block)

/** The same, with every fork run on [on]. */
fun <A> timeoutOrNull(on: Executor, duration: Duration, block: () -> A): A? =
    Unraisable.timeoutOrNull(on, duration) { block() }
