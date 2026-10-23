package io.github.matthewjones372.lark.arrow

import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.Fail
import io.github.matthewjones372.lark.fail.NonEmptyList
import io.github.matthewjones372.lark.fail.attempt
import io.github.matthewjones372.lark.fail.fail
import io.github.matthewjones372.lark.fail.fold
import io.github.matthewjones372.lark.fail.nonEmptyListOf

fun <E, A> Either<E, A>.toArrow(): arrow.core.Either<E, A> =
    fold({ arrow.core.Either.Left(it) }, { arrow.core.Either.Right(it) })

fun <E, A> arrow.core.Either<E, A>.toLark(): Either<E, A> = fold({ Either.Left(it) }, { Either.Right(it) })

// Both lists are non-empty by construction, so neither conversion has a case to answer.
fun <A> NonEmptyList<A>.toArrow(): arrow.core.NonEmptyList<A> = arrow.core.NonEmptyList(head, tail)

fun <A> arrow.core.NonEmptyList<A>.toLark(): NonEmptyList<A> = nonEmptyListOf(head) + tail

/** Runs Lark code inside an Arrow `Raise`: a [fail] in [block] raises its error here. */
fun <E, A> Raise<E>.lark(block: context(Fail<E>) () -> A): A = attempt(block).fold({ raise(it) }, { it })

/** Runs Arrow code inside a Lark [Fail]: a raise in [block] fails with its error here. */
context(_: Fail<E>)
fun <E, A> arrow(block: Raise<E>.() -> A): A = either(block).fold({ fail(it) }, { it })
