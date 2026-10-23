package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.Resource

/**
 * Tests that share one [R], written as a Kotlin `object` so the `lark` engine finds it with no annotation.
 * Each test is registered from `init` with [test], and a test fails by throwing.
 */
abstract class Suite<R> {

    /** Acquired once for the suite, handed to each test, and released after the last. */
    abstract val shared: Resource<R>

    private val registered = LinkedHashMap<String, (R) -> Unit>()

    internal val tests: Map<String, (R) -> Unit> get() = registered.toMap()

    protected fun test(name: String, body: (R) -> Unit) {
        require(name !in registered) { "${javaClass.name} already has a test named \"$name\"; a name is its ID" }
        registered[name] = body
    }
}
