package io.github.matthewjones372.lark.assert

/** Asserts this equals [expected], and returns it. */
infix fun <A> A.shouldBe(expected: A): A {
    if (this != expected) mismatch(expected, this)
    return this
}

/** Runs [block], asserts it threw a [T], and returns what it threw. */
inline fun <reified T : Throwable> shouldThrow(block: () -> Any?): T {
    val thrown = try {
        block()
        null
    } catch (caught: Throwable) {
        caught
    }
    val wanted = "a ${T::class.qualifiedName} thrown"
    return when (thrown) {
        is T -> thrown
        null -> mismatch(expected = null, actual = "nothing thrown", expectedText = wanted)
        else -> mismatch(expected = null, actual = thrown, expectedText = wanted, cause = thrown)
    }
}
