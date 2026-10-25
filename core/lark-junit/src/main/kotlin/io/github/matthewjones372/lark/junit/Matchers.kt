@file:MustUseReturnValues
@file:OptIn(FailureSeam::class)

package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.FailureSeam
import io.github.matthewjones372.lark.assert.Miss
import io.github.matthewjones372.lark.assert.empty
import io.github.matthewjones372.lark.assert.equal
import io.github.matthewjones372.lark.assert.holding
import io.github.matthewjones372.lark.assert.holdingKey
import io.github.matthewjones372.lark.assert.missing
import io.github.matthewjones372.lark.assert.missingAny
import io.github.matthewjones372.lark.assert.missingInAnyOrder
import io.github.matthewjones372.lark.assert.missingKey
import io.github.matthewjones372.lark.assert.notAtLeast
import io.github.matthewjones372.lark.assert.notAtMost
import io.github.matthewjones372.lark.assert.notEmpty
import io.github.matthewjones372.lark.assert.notEqual
import io.github.matthewjones372.lark.assert.notGreaterThan
import io.github.matthewjones372.lark.assert.notInstanceOf
import io.github.matthewjones372.lark.assert.notLessThan
import io.github.matthewjones372.lark.assert.notSameAs
import io.github.matthewjones372.lark.assert.notThrown
import io.github.matthewjones372.lark.assert.sameAs
import io.github.matthewjones372.lark.assert.wasNull
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.nonFatalOrThrow

// The matchers: each returns Expectations on the check its lark-assert namesake throws on, so the two say the same.

/** Holds if this equals [expected]; a failure carries both for an IDE's diff. */
infix fun <A> A.shouldBe(expected: A): Expectations = verdict(notEqual(expected))

/** Holds if this does not equal [unexpected]. */
infix fun <A> A.shouldNotBe(unexpected: A): Expectations = verdict(equal(unexpected))

/** Holds if this is a [Either.Right] holding [expected]. */
infix fun <E, A> Either<E, A>.shouldBeRight(expected: A): Expectations = verdict(notEqual(Either.Right(expected)))

/** Holds if this is a [Either.Left] holding [expected]. */
infix fun <E, A> Either<E, A>.shouldBeLeft(expected: E): Expectations = verdict(notEqual(Either.Left(expected)))

/** Holds if this string contains [part]; a failure carries the string. */
infix fun String?.shouldContain(part: String): Expectations = verdict(missing(part))

/** Holds if this string does not contain [part]. */
infix fun String?.shouldNotContain(part: String): Expectations = verdict(holding(part))

/** Holds if [element] is among this collection's elements; a failure carries the elements. */
infix fun <T> Iterable<T>.shouldContain(element: T): Expectations = verdict(missing(element))

/** Holds if [element] is not among this collection's elements. */
infix fun <T> Iterable<T>.shouldNotContain(element: T): Expectations = verdict(holding(element))

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

/** Holds if this has exactly [elements], each as many times, in any order; fails carrying the elements found. */
infix fun <T> Iterable<T>.shouldContainExactlyInAnyOrder(elements: Iterable<T>): Expectations =
    verdict(missingInAnyOrder(elements))

/** Holds if this has exactly [elements], each as many times, in any order. */
fun <T> Iterable<T>.shouldContainExactlyInAnyOrder(vararg elements: T): Expectations =
    shouldContainExactlyInAnyOrder(elements.asList())

/** Holds if this has every one of [elements], in any order and among others; fails naming those missing. */
infix fun <T> Iterable<T>.shouldContainAll(elements: Iterable<T>): Expectations = verdict(missingAny(elements))

/** Holds if this has an entry for [key]; fails carrying the map. */
infix fun <K> Map<K, *>.shouldContainKey(key: K): Expectations = verdict(missingKey(key))

/** Holds if this has no entry for [key]. */
infix fun <K> Map<K, *>.shouldNotContainKey(key: K): Expectations = verdict(holdingKey(key))

/** Holds if this maps [entry]'s key to its value. */
infix fun <K, V> Map<K, V>.shouldContain(entry: Pair<K, V>): Expectations = verdict(missing(entry))

/** Holds if this has no elements. */
fun Iterable<*>.shouldBeEmpty(): Expectations = verdict(notEmpty())

/** Holds if this has an element. */
fun Iterable<*>.shouldNotBeEmpty(): Expectations = verdict(empty())

/** Holds if this has no entries. */
fun Map<*, *>.shouldBeEmpty(): Expectations = verdict(notEmpty())

/** Holds if this has an entry. */
fun Map<*, *>.shouldNotBeEmpty(): Expectations = verdict(empty())

/** Holds if this is not null and [then], given it, holds too. [then] runs only on a value. */
inline fun <A : Any> A?.shouldNotBeNull(then: (A) -> Expectations): Expectations =
    if (this != null) held() and then(this) else verdict(wasNull())

/** Holds if this is not null. */
fun Any?.shouldNotBeNull(): Expectations = verdict(if (this == null) wasNull() else null)

/** Holds if this is less than [bound]. */
infix fun <T : Comparable<T>> T.shouldBeLessThan(bound: T): Expectations = verdict(notLessThan(bound))

/** Holds if this is at most [bound]. */
infix fun <T : Comparable<T>> T.shouldBeLessThanOrEqual(bound: T): Expectations = verdict(notAtMost(bound))

/** Holds if this is greater than [bound]. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThan(bound: T): Expectations = verdict(notGreaterThan(bound))

/** Holds if this is at least [bound]. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThanOrEqual(bound: T): Expectations = verdict(notAtLeast(bound))

/** A check that held, or the failure it missed with, at the line of the test that checked. */
@PublishedApi
internal fun verdict(miss: Miss?): Expectations =
    if (miss == null) passed(1) else mismatchedHere(miss.expected, miss.actual, miss.expectedText, miss.cause)
