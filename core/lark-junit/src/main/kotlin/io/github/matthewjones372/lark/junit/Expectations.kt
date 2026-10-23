@file:MustUseReturnValues

package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.FailureSeam
import io.github.matthewjones372.lark.assert.failureAt
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError

/**
 * What a test's checks found, as a value: every failure is kept, and none is thrown until the test has returned.
 * Built with [expect]; the return-value checker rejects a call whose result is dropped.
 */
class Expectations internal constructor(internal val failures: List<AssertionFailedError>) {

    /** Both sides' failures, so neither hides the other's. */
    infix fun and(other: Expectations): Expectations = Expectations(failures + other.failures)

    /** Passes if either side does, and otherwise fails with both sides' failures. */
    infix fun or(other: Expectations): Expectations =
        if (failures.isEmpty() || other.failures.isEmpty()) passed else this and other

    /** Passes if this failed, and fails at the line that negated it if this passed. */
    operator fun not(): Expectations =
        if (failures.isEmpty()) failedHere("expected: a failure\nbut was:  a pass") else passed

    /** Throws nothing if every expectation held, the one failure alone, or all of them as one error. */
    internal fun verify(test: String) {
        when (failures.size) {
            0 -> Unit
            1 -> throw failures.single()
            else -> throw MultipleFailuresError(test, failures)
        }
    }
}

/** Holds if [condition] is true; otherwise fails, naming this line of the test and quoting its source. */
fun expect(condition: Boolean): Expectations =
    if (condition) passed else failedHere("expected: true\nbut was:  false")

private val passed = Expectations(emptyList())

// This file's own frames, so a failure's first frame is the test's.
private val own = setOf(Expectations::class.java.name, Expectations::class.java.name + "Kt")

@OptIn(FailureSeam::class)
private fun failedHere(message: String): Expectations =
    Expectations(listOf(failureAt(message, skipping = { it.className in own })))
