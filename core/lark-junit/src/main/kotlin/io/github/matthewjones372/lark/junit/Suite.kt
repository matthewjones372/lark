package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.Limit
import io.github.matthewjones372.lark.Resource

/**
 * Tests that share one [R], written as a Kotlin `object` so the `lark` engine finds it with no annotation.
 * Each test is registered from `init` with [test] and returns its [Expectations]; a test that throws fails with
 * what it threw. Tests run as forks on virtual threads, at most [limit] at once; with [concurrent] false, each
 * runs alone. The suite's [aspects] wrap each test's.
 * Each test logs and measures into a capture of its own, and a failed test's log lines are reported with it.
 * A [story] told without a title takes the test's name, and its transcript is one of those log lines.
 */
abstract class Suite<R>(
    internal val limit: Limit = Limit.fixed(Runtime.getRuntime().availableProcessors()),
    internal val concurrent: Boolean = true,
    private val aspects: Aspect = Aspect.none,
) {

    /**
     * Acquired once for the suite, handed to each test, and released after the last. If acquiring throws, each
     * test fails with that throw, and what was installed before it is released.
     */
    abstract val shared: Resource<R>

    private val registered = LinkedHashMap<String, Registered<R>>()

    internal val tests: Map<String, Registered<R>> get() = registered.toMap()

    /** Registers a test that runs as its [aspects] say, inside the suite's. */
    protected fun test(name: String, aspects: Aspect = Aspect.none, body: (R) -> Expectations) {
        require(name !in registered) { "${javaClass.name} already has a test named \"$name\"; a name is its ID" }
        registered[name] = Registered(body, this.aspects + aspects + if (concurrent) Aspect.none else alone)
    }

    internal class Registered<R>(val body: (R) -> Expectations, val aspects: Aspect)
}
