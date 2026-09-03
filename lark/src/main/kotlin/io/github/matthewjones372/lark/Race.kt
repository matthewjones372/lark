package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.left
import arrow.core.right

/**
 * Runs both branches on forks of their own and answers with the first to return; the losers are interrupted
 * and their outcomes dropped, and a branch that fails first loses the race for everyone.
 */
fun <E, A, B> Flock<E>.raceN(fa: Flock<E>.() -> A, fb: Flock<E>.() -> B): Either<A, B> {
    val flight = Flight(this)
    val a = flight.fork(fa)
    val b = flight.fork(fb)
    flight.settleFirst()
    return if (a.answered()) a.await().left() else b.await().right()
}

/** Three branches, the winner on the side it was given. */
fun <E, A, B, C> Flock<E>.raceN(
    fa: Flock<E>.() -> A,
    fb: Flock<E>.() -> B,
    fc: Flock<E>.() -> C,
): Either<A, Either<B, C>> {
    val flight = Flight(this)
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
