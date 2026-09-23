package io.github.matthewjones372.lark.structured

import arrow.core.raise.Raise
import arrow.core.raise.either
import java.util.concurrent.StructuredTaskScope

internal sealed interface Outcome<out E, out T>

internal class Returned<T>(val value: T) : Outcome<Nothing, T>

internal sealed interface Failure<out E> : Outcome<E, Nothing>

internal class Raised<E>(val error: E) : Failure<E>

internal class Thrown(val throwable: Throwable) : Failure<Nothing>

/**
 * A branch runs under a `Raise` of its own, since a `Raise` never crosses a thread, and whatever it does is
 * kept as a value: a throw left to end the subtask would reach the JDK as a failure nobody asked for.
 */
internal fun <E, T> capture(body: Raise<E>.() -> T): Outcome<E, T> =
    try {
        either { body() }.fold({ Raised(it) }, { Returned(it) })
    } catch (t: Throwable) {
        Thrown(t)
    }

internal fun <E> Raise<E>.surface(failure: Failure<E>): Nothing = when (failure) {
    is Raised -> raise(failure.error)
    is Thrown -> throw failure.throwable
}

/** A joined branch's value, or its failure raised or thrown on the caller's thread. */
internal fun <E, T> Raise<E>.valueOf(subtask: StructuredTaskScope.Subtask<Outcome<E, T>>): T =
    when (val outcome = subtask.get()) {
        is Returned -> outcome.value
        is Failure -> surface(outcome)
    }
