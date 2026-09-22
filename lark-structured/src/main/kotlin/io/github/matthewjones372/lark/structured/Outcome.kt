package io.github.matthewjones372.lark.structured

import arrow.core.raise.Raise
import arrow.core.raise.either

internal sealed interface Outcome<out E, out T>

internal class Returned<T>(val value: T) : Outcome<Nothing, T>

internal sealed interface Failure<out E> : Outcome<E, Nothing>

internal class Raised<E>(val error: E) : Failure<E>

internal class Thrown(val throwable: Throwable) : Failure<Nothing>

/**
 * A fork's body runs under a scope of its own, named after the fork, and whatever it does is kept as a
 * value: a throw left to end the subtask would reach the JDK as a failure nobody asked for.
 */
internal fun <E, T> capture(name: String, block: StructuredScope<E>.() -> T): Outcome<E, T> =
    try {
        either { runScope(this, name, null, block) }.fold({ Raised(it) }, { Returned(it) })
    } catch (t: Throwable) {
        Thrown(t)
    }

internal fun <E> Raise<E>.surface(failure: Failure<E>): Nothing = when (failure) {
    is Raised -> raise(failure.error)
    is Thrown -> throw failure.throwable
}

internal fun Failure<*>.isInterrupt(): Boolean = this is Thrown && throwable is InterruptedException
