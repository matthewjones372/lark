package io.github.matthewjones372.lark.assert

/** Asserts this is less than [bound], and returns it. */
infix fun <T : Comparable<T>> T.shouldBeLessThan(bound: T): T = compared(this < bound, "less than $bound")

/** Asserts this is at most [bound], and returns it. */
infix fun <T : Comparable<T>> T.shouldBeLessThanOrEqual(bound: T): T = compared(this <= bound, "at most $bound")

/** Asserts this is greater than [bound], and returns it. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThan(bound: T): T = compared(this > bound, "greater than $bound")

/** Asserts this is at least [bound], and returns it. */
infix fun <T : Comparable<T>> T.shouldBeGreaterThanOrEqual(bound: T): T =
    compared(this >= bound, "at least $bound")

/** Asserts this lies in [range], its ends included, and returns it. */
infix fun <T : Comparable<T>> T.shouldBeInRange(range: ClosedRange<T>): T = compared(this in range, "in $range")

private fun <T> T.compared(holds: Boolean, wanted: String): T {
    if (!holds) mismatch(expected = null, actual = this, expectedText = wanted)
    return this
}
