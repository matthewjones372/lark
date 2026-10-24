@file:MustUseReturnValues

package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.FailureSeam
import io.github.matthewjones372.lark.assert.failureAt
import io.github.matthewjones372.lark.assert.mismatchAt
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.nonFatalOrThrow
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError

/**
 * What a test's checks found, as a value: every failure is kept, and none is thrown until the test has returned.
 * Built with [expect] and the matchers beside it; the return-value checker rejects a call whose result is dropped.
 */
class Expectations internal constructor(
    internal val failures: List<AssertionFailedError>,
    private val checked: Int,
) {

    /** The one failure, or all of them as one error; a story marks a step failed with it. */
    internal val failure: Throwable? by lazy {
        when (failures.size) {
            0 -> null
            1 -> failures.single()
            else -> MultipleFailuresError(null, failures).also { it.stackTrace = failures.first().stackTrace }
        }
    }

    /** Both sides' failures, so neither hides the other's. */
    infix fun and(other: Expectations): Expectations = Expectations(failures + other.failures, checked + other.checked)

    /** Passes if either side does, and otherwise fails with both sides' failures. */
    infix fun or(other: Expectations): Expectations =
        if (failures.isEmpty() || other.failures.isEmpty()) passed(checked + other.checked) else this and other

    /** Passes if this failed, and fails at the line that negated it if this passed. */
    operator fun not(): Expectations =
        if (failures.isEmpty()) failedHere("expected: a failure\nbut was:  a pass") else passed(1)

    /** Throws nothing if every expectation held, the one failure alone, or all of them as one error. */
    internal fun verify(test: String) {
        val heading = "$test: ${failures.size} of $checked expectations failed"
        when (failures.size) {
            0 -> Unit
            1 -> throw failures.single().headed(heading)
            else -> throw MultipleFailuresError(heading, failures)
        }
    }
}

/** Holds if [condition] is true; otherwise fails, naming this line of the test and quoting its source. */
fun expect(condition: Boolean): Expectations =
    if (condition) passed(1) else failedHere("expected: true\nbut was:  false")

/** Holds if [condition] is true; otherwise fails with [message], naming this line. The power-assert plugin's target. */
fun expect(condition: Boolean, message: () -> String): Expectations =
    if (condition) passed(1) else failedHere(message().trim(), quoting = false)

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

/** Holds if [element] is among this collection's elements; fails carrying the elements. */
infix fun <T> Iterable<T>.shouldContain(element: T): Expectations =
    if (element in this) passed(1) else mismatchedHere(null, toList(), "a collection containing $element")

/** Holds if [element] is not among this collection's elements. */
infix fun <T> Iterable<T>.shouldNotContain(element: T): Expectations =
    if (element !in this) passed(1) else mismatchedHere(null, toList(), "a collection without $element")

/** Holds if this is the very object [expected] is, not only an equal one. */
infix fun <A> A.shouldBeSameInstanceAs(expected: A): Expectations =
    if (this === expected) passed(1) else mismatchedHere(null, identified(this), identified(expected))

/** Holds if this is not the very object [unexpected] is, though it may equal it. */
infix fun <A> A.shouldNotBeSameInstanceAs(unexpected: A): Expectations =
    if (this !==
        unexpected
    ) passed(1) else mismatchedHere(null, identified(this), "anything but ${identified(unexpected)}")

/** Holds if [block] throws a [T]; fails, naming this line, if it throws nothing or something else. */
inline fun <reified T : Throwable> shouldThrow(block: () -> Any?): Expectations = shouldThrow<T>(block) { threw() }

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
        is T -> threw() and then(thrown)
        else -> notThrown("a ${T::class.qualifiedName} thrown", thrown?.nonFatalOrThrow())
    }
}

/** The check [shouldThrow] passed. */
@PublishedApi
internal fun threw(): Expectations = passed(1)

/** The check [shouldThrow] failed: nothing thrown, or [thrown], carried as the cause. */
@PublishedApi
internal fun notThrown(wanted: String, thrown: Throwable?): Expectations =
    mismatchedHere(null, thrown ?: "nothing thrown", wanted, thrown)

private fun passed(checked: Int) = Expectations(emptyList(), checked)

private fun quoted(text: String) = "\"$text\""

/** A value with its identity, for a check that two are or are not the same object. */
private fun identified(value: Any?): String =
    value?.let { "$it @${Integer.toHexString(System.identityHashCode(it))}" } ?: "null"

/** The same failure, with [heading] above its message and the same expected and actual. */
private fun AssertionFailedError.headed(heading: String): AssertionFailedError {
    val text = "$heading\n$message"
    val headed = when {
        isExpectedDefined -> AssertionFailedError(text, expected, actual, cause)
        else -> AssertionFailedError(text, cause)
    }
    return headed.also { it.stackTrace = stackTrace }
}

// This file's own frames, so a failure's first frame is the test's.
private val own = setOf(Expectations::class.java.name, Expectations::class.java.name + "Kt")

@OptIn(FailureSeam::class)
private fun failedHere(message: String, quoting: Boolean = true): Expectations =
    Expectations(listOf(failureAt(message, skipping = { it.className in own }, quoting)), 1)

@OptIn(FailureSeam::class)
private fun mismatchedHere(
    expected: Any?,
    actual: Any?,
    expectedText: String? = null,
    cause: Throwable? = null,
): Expectations = Expectations(
    listOf(
        mismatchAt(expected, actual, skipping = {
            it.className in own
        }, expectedText, cause),
    ),
    1,
)
