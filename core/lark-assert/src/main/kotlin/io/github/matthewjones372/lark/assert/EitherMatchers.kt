package io.github.matthewjones372.lark.assert

import io.github.matthewjones372.lark.fail.Either

/** Asserts this is a [Either.Right] and returns its value. */
fun <E, A> Either<E, A>.shouldBeRight(): A = when (this) {
    is Either.Right -> value
    is Either.Left -> mismatch(expected = null, actual = this, expectedText = "a Right")
}

/** Asserts this is a [Either.Right] holding [expected], and returns it. */
infix fun <E, A> Either<E, A>.shouldBeRight(expected: A): A {
    val wanted = Either.Right(expected)
    if (this != wanted) mismatch(wanted, this)
    return expected
}

/** Asserts this is a [Either.Left] and returns its error. */
fun <E, A> Either<E, A>.shouldBeLeft(): E = when (this) {
    is Either.Left -> value
    is Either.Right -> mismatch(expected = null, actual = this, expectedText = "a Left")
}

/** Asserts this is a [Either.Left] holding [expected], and returns it. */
infix fun <E, A> Either<E, A>.shouldBeLeft(expected: E): E {
    val wanted = Either.Left(expected)
    if (this != wanted) mismatch(wanted, this)
    return expected
}
