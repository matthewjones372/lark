package io.github.matthewjones372.lark.assert

import io.github.matthewjones372.lark.fail.Either

/** Asserts this is a [Either.Right] and returns its value. */
fun <E, A> Either<E, A>.shouldBeRight(): A = when (this) {
    is Either.Right -> value
    is Either.Left -> throw AssertionError("expected a Right but was $this")
}

/** Asserts this is a [Either.Right] holding [expected], and returns it. */
infix fun <E, A> Either<E, A>.shouldBeRight(expected: A): A {
    if (this != Either.Right(expected)) throw AssertionError("expected ${Either.Right(expected)} but was $this")
    return expected
}

/** Asserts this is a [Either.Left] and returns its error. */
fun <E, A> Either<E, A>.shouldBeLeft(): E = when (this) {
    is Either.Left -> value
    is Either.Right -> throw AssertionError("expected a Left but was $this")
}

/** Asserts this is a [Either.Left] holding [expected], and returns it. */
infix fun <E, A> Either<E, A>.shouldBeLeft(expected: E): E {
    if (this != Either.Left(expected)) throw AssertionError("expected ${Either.Left(expected)} but was $this")
    return expected
}
