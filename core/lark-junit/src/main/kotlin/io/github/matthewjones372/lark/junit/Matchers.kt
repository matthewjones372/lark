@file:MustUseReturnValues
@file:OptIn(FailureSeam::class)

package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.FailureSeam
import io.github.matthewjones372.lark.assert.Miss
import io.github.matthewjones372.lark.assert.blank
import io.github.matthewjones372.lark.assert.equal
import io.github.matthewjones372.lark.assert.holding
import io.github.matthewjones372.lark.assert.missing
import io.github.matthewjones372.lark.assert.noMatchFor
import io.github.matthewjones372.lark.assert.notAtLeast
import io.github.matthewjones372.lark.assert.notAtMost
import io.github.matthewjones372.lark.assert.notEndingWith
import io.github.matthewjones372.lark.assert.notEqual
import io.github.matthewjones372.lark.assert.notGreaterThan
import io.github.matthewjones372.lark.assert.notIn
import io.github.matthewjones372.lark.assert.notInstanceOf
import io.github.matthewjones372.lark.assert.notLessThan
import io.github.matthewjones372.lark.assert.notMatching
import io.github.matthewjones372.lark.assert.notNull
import io.github.matthewjones372.lark.assert.notSameAs
import io.github.matthewjones372.lark.assert.notStartingWith
import io.github.matthewjones372.lark.assert.notThrown
import io.github.matthewjones372.lark.assert.sameAs
import io.github.matthewjones372.lark.assert.wasNull
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.nonFatalOrThrow

// The matchers: each returns Expectations on the check its lark-assert namesake throws on, so the two say the same.

/** Holds if this equals [expected]; fails as lark-assert's `shouldBe` does, carrying both for an IDE's diff. */
infix fun <A> A.shouldBe(expected: A): Expectations = verdict(notEqual(expected))

/** Holds if this does not equal [unexpected]. */
infix fun <A> A.shouldNotBe(unexpected: A): Expectations = verdict(equal(unexpected))

/** Holds if this is a [Either.Right] holding [expected]. */
infix fun <E, A> Either<E, A>.shouldBeRight(expected: A): Expectations = verdict(notEqual(Either.Right(expected)))

/** Holds if this is a [Either.Left] holding [expected]. */
infix fun <E, A> Either<E, A>.shouldBeLeft(expected: E): Expectations = verdict(notEqual(Either.Left(expected)))

/** Holds if this is a [Either.Right] and [then], given its value, holds too. [then] runs only on a Right. */
inline fun <E, A> Either<E, A>.shouldBeRight(then: (A) -> Expectations): Expectations = when (this) {
    is Either.Right -> held() and then(value)
    is Either.Left -> notEither(this, "a Right")
}

/** Holds if this is a [Either.Left] and [then], given its error, holds too. [then] runs only on a Left. */
inline fun <E, A> Either<E, A>.shouldBeLeft(then: (E) -> Expectations): Expectations = when (this) {
    is Either.Left -> held() and then(value)
    is Either.Right -> notEither(this, "a Left")
}

/** Holds if this string contains [part]; fails as lark-assert's `shouldContain` does, carrying the string. */
infix fun String?.shouldContain(part: String): Expectations = verdict(missing(part))

/** Holds if this string does not contain [part]. */
infix fun String?.shouldNotContain(part: String): Expectations = verdict(holding(part))

/** Holds if this string starts with [prefix]. */
infix fun String?.shouldStartWith(prefix: String): Expectations = verdict(notStartingWith(prefix))

/** Holds if this string ends with [suffix]. */
infix fun String?.shouldEndWith(suffix: String): Expectations = verdict(notEndingWith(suffix))

/** Holds if this string has something in it besides whitespace. */
fun String?.shouldNotBeBlank(): Expectations = verdict(blank())

/** Holds if the whole of this string matches [pattern]. */
infix fun String?.shouldMatch(pattern: String): Expectations = shouldMatch(Regex(pattern))

/** Holds if the whole of this string matches [pattern]. */
infix fun String?.shouldMatch(pattern: Regex): Expectations = verdict(notMatching(pattern))

/** Holds if some part of this string matches [pattern]. */
infix fun String?.shouldContain(pattern: Regex): Expectations = verdict(noMatchFor(pattern))

/** Holds if this is the very object [expected] is, not only an equal one. */
infix fun <A> A.shouldBeSameInstanceAs(expected: A): Expectations = verdict(notSameAs(expected))

/** Holds if this is not the very object [unexpected] is, though it may equal it. */
infix fun <A> A.shouldNotBeSameInstanceAs(unexpected: A): Expectations = verdict(sameAs(unexpected))

/** Holds if [block] throws a [T]; fails, naming this line, if it throws nothing or something else. */
inline fun <reified T : Throwable> shouldThrow(block: () -> Any?): Expectations = shouldThrow<T>(block) { held() }

/**
 * Holds if [block] throws a [T] and [then], given what it threw, holds too. [then] runs only on a [T]. An interrupt,
 * a cancellation or a VM error that is not a [T] goes on up, so the test stops as it would have without the check.
 */
inline fun <reified T : Throwable> shouldThrow(block: () -> Any?, then: (T) -> Expectations): Expectations {
    @Suppress("TooGenericExceptionCaught") // catching everything is the point; nonFatalOrThrow sorts it
    val thrown = try {
        val _ = block()
        null
    } catch (caught: Throwable) {
        caught
    }
    return when (thrown) {
        is T -> held() and then(thrown)
        else -> verdict(notThrown("a ${T::class.qualifiedName} thrown", thrown?.nonFatalOrThrow()))
    }
}

/**
 * Holds if this is a [T] and [then], given it as one, holds too. [then] runs only on a [T]; a miss names the type
 * wanted and the value found, with its type.
 */
inline fun <reified T> Any?.shouldBeInstanceOf(then: (T) -> Expectations): Expectations =
    if (this is T) held() and then(this) else verdict(notInstanceOf<T>())

/** Holds if this is a [T]. */
inline fun <reified T> Any?.shouldBeInstanceOf(): Expectations = shouldBeInstanceOf<T> { held() }

/** Holds if this is not null and [then], given it, holds too. [then] runs only on a value. */
inline fun <A : Any> A?.shouldNotBeNull(then: (A) -> Expectations): Expectations =
    if (this != null) held() and then(this) else verdict(wasNull())

/** Holds if this is not null. */
fun Any?.shouldNotBeNull(): Expectations = verdict(if (this == null) wasNull() else null)

/** Holds if this is null. */
fun Any?.shouldBeNull(): Expectations = verdict(notNull())

/** Holds if this is less than [bound]. */
infix fun <T : Comparable<T>> T.shouldBeLessThan(bound: T): Expectations = verdict(notLessThan(bound))

/** Holds if this is at most [bound]. */
infix fun <T : Comparable<T>> T.shouldBeLessThanOrEqual(bound: T): Expectations = verdict(notAtMost(bound))

/** Holds if this is greater than [bound]. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThan(bound: T): Expectations = verdict(notGreaterThan(bound))

/** Holds if this is at least [bound]. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThanOrEqual(bound: T): Expectations = verdict(notAtLeast(bound))

/** Holds if this is in [range], its ends included. */
infix fun <T : Comparable<T>> T.shouldBeInRange(range: ClosedRange<T>): Expectations = verdict(notIn(range))

/** The check [shouldBeRight] or [shouldBeLeft] found the other side, [either]. */
@PublishedApi
internal fun notEither(either: Either<*, *>, wanted: String): Expectations = mismatchedHere(null, either, wanted)

/** A shared lark-assert check's [miss] as an expectation at the test's line, or a pass when there is none. */
@PublishedApi
internal fun verdict(miss: Miss?): Expectations =
    miss?.let { mismatchedHere(it.expected, it.actual, it.expectedText, it.cause) } ?: passed(1)
