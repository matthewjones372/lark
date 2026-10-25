@file:MustUseReturnValues

package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.FailureSeam
import io.github.matthewjones372.lark.assert.failureAt
import io.github.matthewjones372.lark.assert.mismatchAt
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError
import io.github.matthewjones372.lark.assert.withClue as clued

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

/** [block]'s expectations, each failure in it saying [clue] first. Blocks nest, the outermost clue first. */
fun withClue(clue: Any?, block: () -> Expectations): Expectations = clued(clue, block)

/** Holds if [check] holds for every element, keeping every element's failures. Holds on no elements, checking none. */
inline fun <T> Iterable<T>.forAll(check: (T) -> Expectations): Expectations =
    fold(nothingChecked()) { all, element -> all and check(element) }

/** A check that passed, for the matchers that take a lambda. */
@PublishedApi
internal fun held(): Expectations = passed(1)

/** No checks at all, for [forAll] to start from. */
@PublishedApi
internal fun nothingChecked(): Expectations = passed(0)

internal fun passed(checked: Int) = Expectations(emptyList(), checked)

/** The same failure, with [heading] above its message and the same expected and actual. */
private fun AssertionFailedError.headed(heading: String): AssertionFailedError {
    val text = "$heading\n$message"
    val headed = when {
        isExpectedDefined -> AssertionFailedError(text, expected, actual, cause)
        else -> AssertionFailedError(text, cause)
    }
    return headed.also { it.stackTrace = stackTrace }
}

// This module's frames that build a failure, so a failure's first frame is the test's.
private val own = setOf(
    Expectations::class.java.name,
    Expectations::class.java.name + "Kt",
    Expectations::class.java.packageName + ".MatchersKt",
    Expectations::class.java.packageName + ".CollectionMatchersKt",
)

@OptIn(FailureSeam::class)
internal fun failedHere(message: String, quoting: Boolean = true): Expectations =
    Expectations(listOf(failureAt(message, skipping = { it.className in own }, quoting)), 1)

@OptIn(FailureSeam::class)
internal fun mismatchedHere(
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
