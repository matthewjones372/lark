@file:MustUseReturnValues

package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.FailureSeam
import io.github.matthewjones372.lark.assert.Miss
import io.github.matthewjones372.lark.assert.notIn
import io.github.matthewjones372.lark.assert.notNull
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.nonFatalOrThrow

// The matchers: each returns Expectations, and fails as its lark-assert namesake does, naming the test's line.

/** Holds if this equals [expected]; fails as lark-assert's `shouldBe` does, carrying both for an IDE's diff. */
infix fun <A> A.shouldBe(expected: A): Expectations =
    if (this == expected) passed(1) else mismatchedHere(expected, this)

/** Holds if this does not equal [unexpected]. */
infix fun <A> A.shouldNotBe(unexpected: A): Expectations =
    if (this != unexpected) passed(1) else mismatchedHere(null, this, "not $unexpected")

/** Holds if this is a [Either.Right] holding [expected]. */
infix fun <E, A> Either<E, A>.shouldBeRight(expected: A): Expectations = this shouldBe Either.Right(expected)

/** Holds if this is a [Either.Left] holding [expected]. */
infix fun <E, A> Either<E, A>.shouldBeLeft(expected: E): Expectations = this shouldBe Either.Left(expected)

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
infix fun String?.shouldContain(part: String): Expectations =
    if (this != null &&
        part in this
    ) passed(1) else mismatchedHere(null, this?.let(::quoted), "a string containing ${quoted(part)}")

/** Holds if this string does not contain [part]. */
infix fun String?.shouldNotContain(part: String): Expectations =
    if (this != null &&
        part !in this
    ) passed(1) else mismatchedHere(null, this?.let(::quoted), "a string without ${quoted(part)}")

/** Holds if this is the very object [expected] is, not only an equal one. */
infix fun <A> A.shouldBeSameInstanceAs(expected: A): Expectations =
    if (this === expected) passed(1) else mismatchedHere(null, identified(this), identified(expected))

/** Holds if this is not the very object [unexpected] is, though it may equal it. */
infix fun <A> A.shouldNotBeSameInstanceAs(unexpected: A): Expectations =
    if (this !==
        unexpected
    ) passed(1) else mismatchedHere(null, identified(this), "anything but ${identified(unexpected)}")

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
        else -> notThrown("a ${T::class.qualifiedName} thrown", thrown?.nonFatalOrThrow())
    }
}

/**
 * Holds if this is a [T] and [then], given it as one, holds too. [then] runs only on a [T]; a miss names the type
 * wanted and the value found, with its type.
 */
inline fun <reified T> Any?.shouldBeInstanceOf(then: (T) -> Expectations): Expectations =
    if (this is T) held() and then(this) else notInstance("an instance of ${T::class.qualifiedName}", this)

/** Holds if this is a [T]. */
inline fun <reified T> Any?.shouldBeInstanceOf(): Expectations = shouldBeInstanceOf<T> { held() }

/** Holds if this is not null and [then], given it, holds too. [then] runs only on a value. */
inline fun <A : Any> A?.shouldNotBeNull(then: (A) -> Expectations): Expectations =
    if (this != null) held() and then(this) else wasNull()

/** Holds if this is not null. */
fun Any?.shouldNotBeNull(): Expectations = if (this != null) passed(1) else wasNull()

/** Holds if this is null. */
@OptIn(FailureSeam::class)
fun Any?.shouldBeNull(): Expectations = verdict(notNull())

/** Holds if this is less than [bound]. */
infix fun <T : Comparable<T>> T.shouldBeLessThan(bound: T): Expectations = compared(this < bound, "less than $bound")

/** Holds if this is at most [bound]. */
infix fun <T : Comparable<T>> T.shouldBeLessThanOrEqual(bound: T): Expectations =
    compared(this <= bound, "at most $bound")

/** Holds if this is greater than [bound]. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThan(bound: T): Expectations =
    compared(this > bound, "greater than $bound")

/** Holds if this is at least [bound]. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThanOrEqual(bound: T): Expectations =
    compared(this >= bound, "at least $bound")

/** Holds if this is in [range], its ends included. */
@OptIn(FailureSeam::class)
infix fun <T : Comparable<T>> T.shouldBeInRange(range: ClosedRange<T>): Expectations = verdict(notIn(range))

/** The check [shouldBeRight] or [shouldBeLeft] found the other side, [either]. */
@PublishedApi
internal fun notEither(either: Either<*, *>, wanted: String): Expectations = mismatchedHere(null, either, wanted)

/** The check [shouldNotBeNull] failed. */
@PublishedApi
internal fun wasNull(): Expectations = mismatchedHere(null, null, "a value, not null")

/** The check [shouldBeInstanceOf] failed on [value]. */
@PublishedApi
internal fun notInstance(wanted: String, value: Any?): Expectations =
    mismatchedHere(null, value?.let { "$it of ${it::class.qualifiedName}" } ?: "null", wanted)

/** The check [shouldThrow] failed: nothing thrown, or [thrown], carried as the cause. */
@PublishedApi
internal fun notThrown(wanted: String, thrown: Throwable?): Expectations =
    mismatchedHere(null, thrown ?: "nothing thrown", wanted, thrown)

/** A shared lark-assert check's [miss] as an expectation at the test's line, or a pass when there is none. */
@OptIn(FailureSeam::class)
internal fun verdict(miss: Miss?): Expectations =
    miss?.let { mismatchedHere(it.expected, it.actual, it.expectedText, it.cause) } ?: passed(1)

private fun Any?.compared(holds: Boolean, wanted: String): Expectations =
    if (holds) passed(1) else mismatchedHere(null, this, wanted)

private fun quoted(text: String) = "\"$text\""

/** A value with its identity, for a check that two are or are not the same object. */
private fun identified(value: Any?): String =
    value?.let { "$it @${Integer.toHexString(System.identityHashCode(it))}" } ?: "null"
