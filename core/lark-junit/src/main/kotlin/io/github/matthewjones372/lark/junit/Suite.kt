package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.Limit
import io.github.matthewjones372.lark.Resource

/**
 * Tests that share one [R], written as a Kotlin `object` so the `lark` engine finds it with no annotation.
 * Each test is registered from `init` with [test], and a test fails by throwing. Tests run as forks on
 * virtual threads, at most [limit] at once; with [concurrent] false, each runs alone.
 * Each test logs and measures into a capture of its own, and a failed test's log lines are reported with it.
 */
abstract class Suite<R>(
    internal val limit: Limit = Limit.fixed(Runtime.getRuntime().availableProcessors()),
    internal val concurrent: Boolean = true,
) {

    /**
     * Acquired once for the suite, handed to each test, and released after the last. If acquiring throws, each
     * test fails with that throw, and what was installed before it is released.
     */
    abstract val shared: Resource<R>

    private val registered = LinkedHashMap<String, Registered<R>>()

    internal val tests: Map<String, Registered<R>> get() = registered.toMap()

    /** An [alone] test waits for the tests registered before it, and those after it wait for it. */
    protected fun test(name: String, alone: Boolean = false, body: (R) -> Unit) {
        require(name !in registered) { "${javaClass.name} already has a test named \"$name\"; a name is its ID" }
        registered[name] = Registered(body, alone || !concurrent)
    }

    internal class Registered<R>(val body: (R) -> Unit, val alone: Boolean)
}
