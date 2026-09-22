package io.github.matthewjones372.lark.structured

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.right
import java.util.concurrent.StructuredTaskScope
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

// lark's own combinators, with the same names and shapes, each running its branches as subtasks of a JDK
// StructuredTaskScope opened for that one call. Importing these instead of lark's is the whole switch.

/** Runs both branches at once and combines their values; the first to fail cancels the other and is the answer. */
fun <E, A, B, C> Raise<E>.parZip(fa: Raise<E>.() -> A, fb: Raise<E>.() -> B, f: (A, B) -> C): C =
    Tasks<E, Failure<E>?>(FirstFailure()).use { tasks ->
        val a = tasks.fork(fa)
        val b = tasks.fork(fb)
        tasks.join()?.let { surface(it) }
        f(valueOf(a), valueOf(b))
    }

/** The same for three branches. */
fun <E, A, B, C, D> Raise<E>.parZip(
    fa: Raise<E>.() -> A,
    fb: Raise<E>.() -> B,
    fc: Raise<E>.() -> C,
    f: (A, B, C) -> D,
): D = Tasks<E, Failure<E>?>(FirstFailure()).use { tasks ->
    val a = tasks.fork(fa)
    val b = tasks.fork(fb)
    val c = tasks.fork(fc)
    tasks.join()?.let { surface(it) }
    f(valueOf(a), valueOf(b), valueOf(c))
}

/** Runs [f] over every element at once and answers in the iterable's order; the first to fail is the answer. */
fun <E, A, B> Raise<E>.parMap(iterable: Iterable<A>, f: Raise<E>.(A) -> B): List<B> =
    Tasks<E, Failure<E>?>(FirstFailure()).use { tasks ->
        val subtasks = iterable.map { element -> tasks.fork { f(element) } }
        tasks.join()?.let { surface(it) }
        subtasks.map { valueOf(it) }
    }

/**
 * Runs both branches at once and answers with the first to finish, on the side it was given; the loser is
 * cancelled. A branch that fails first loses the race for everyone.
 */
fun <E, A, B> Raise<E>.raceN(fa: Raise<E>.() -> A, fb: Raise<E>.() -> B): Either<A, B> =
    Tasks<E, StructuredTaskScope.Subtask<*>?>(FirstToFinish()).use { tasks ->
        val a = tasks.fork(fa)
        val b = tasks.fork(fb)
        val winner = checkNotNull(tasks.join()) { "a joined race has a winner" }
        if (winner === a) valueOf(a).left() else valueOf(b).right()
    }

/** Runs [block] with a deadline of [duration]: past it, the block is cancelled and [onTimeout] is raised. */
fun <E, A> Raise<E>.timeout(duration: Duration, onTimeout: () -> E, block: Raise<E>.() -> A): A =
    withDeadline(duration, block) { raise(onTimeout()) }

/** The same, throwing `TimeoutException` past the deadline, as lark's `timeout` does. */
fun <E, A> Raise<E>.timeout(duration: Duration, block: Raise<E>.() -> A): A =
    withDeadline(duration, block) { throw TimeoutException("no answer within $duration") }

/** The same, answering null past the deadline. */
fun <E, A> Raise<E>.timeoutOrNull(duration: Duration, block: Raise<E>.() -> A): A? =
    Tasks<E, Boolean>(Expiry(), duration).use { tasks ->
        val answer = tasks.fork(block)
        if (tasks.join() && !answer.hasAnswered()) null else valueOf(answer)
    }

private inline fun <E, A> Raise<E>.withDeadline(
    duration: Duration,
    noinline block: Raise<E>.() -> A,
    expired: () -> Nothing,
): A = Tasks<E, Boolean>(Expiry(), duration).use { tasks ->
    val answer = tasks.fork(block)
    // An answer that got in before the deadline is still the answer.
    if (tasks.join() && !answer.hasAnswered()) expired() else valueOf(answer)
}
