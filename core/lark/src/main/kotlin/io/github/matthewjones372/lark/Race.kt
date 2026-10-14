package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.right
import java.util.concurrent.Executor

/**
 * Runs both branches on forks of their own and answers with the first to return; the losers are interrupted
 * and their outcomes dropped, and a branch that fails first loses the race for everyone.
 */
fun <E, A, B> Raise<E>.raceN(fa: Raise<E>.() -> A, fb: Raise<E>.() -> B): Either<A, B> = raceN(VirtualThreads, fa, fb)

/** The same, with every fork run on [on]. */
fun <E, A, B> Raise<E>.raceN(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
): Either<A, B> {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    flight.settleFirst()
    return if (a.answered()) a.await().left() else b.await().right()
}

/** Three branches, the winner on the side it was given. */
fun <E, A, B, C> Raise<E>.raceN(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
): Either<A, Either<B, C>> = raceN(VirtualThreads, fa, fb, fc)

/** The same, with every fork run on [on]. */
fun <E, A, B, C> Raise<E>.raceN(
    on: Executor,
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
): Either<A, Either<B, C>> {
    val flight = Flight(this, on)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    val c = flight.fork(fc)
    flight.settleFirst()
    return when {
        a.answered() -> a.await().left()
        b.answered() -> b.await().left().right()
        else -> c.await().right().right()
    }
}

/** The same race for code outside any `Raise`, with plain branches. */
fun <A, B> raceN(fa: () -> A, fb: () -> B): Either<A, B> = raceN(VirtualThreads, fa, fb)

/** The same, with every fork run on [on]. */
fun <A, B> raceN(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
): Either<A, B> = Unraisable.raceN(on, { fa() }, { fb() })

/** Three branches, the winner on the side it was given. */
fun <A, B, C> raceN(fa: () -> A, fb: () -> B, fc: () -> C): Either<A, Either<B, C>> = raceN(VirtualThreads, fa, fb, fc)

/** The same, with every fork run on [on]. */
fun <A, B, C> raceN(
    on: Executor,
    fa: () -> A,
    fb: () -> B,
    fc: () -> C,
): Either<A, Either<B, C>> = Unraisable.raceN(on, { fa() }, { fb() }, { fc() })
