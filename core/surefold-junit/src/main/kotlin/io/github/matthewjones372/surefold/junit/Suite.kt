package io.github.matthewjones372.surefold.junit

import io.github.matthewjones372.surefold.Limit
import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.resource

/**
 * Tests written as a Kotlin `object` so the `surefold` engine finds it with no annotation: a [Suite] when they share
 * nothing, a [SharedSuite] when they share one [R]. Each test is registered from `init`, and every check it makes is
 * recorded as its result; a test that throws fails with what it threw. Tests run as forks on virtual threads, at most
 * [limit] at once; with [concurrent] false, each runs alone. The suite's [aspects] wrap each test's.
 * Each test logs and measures into a capture of its own, and a failed test's log lines are reported with it.
 * A [story] told without a title takes the test's name, and its transcript is one of those log lines.
 */
abstract class AnySuite<R> internal constructor(
    internal val limit: Limit,
    internal val concurrent: Boolean,
    private val aspects: Aspect,
) {

    /** What the suite acquires once, hands to each test, and releases after the last. */
    internal abstract val toAcquire: Resource<R>

    private val registered = LinkedHashMap<String, Registered<R>>()

    internal val tests: Map<String, Registered<R>> get() = registered.toMap()

    internal fun register(name: String, aspects: Aspect, body: (R) -> Unit) {
        require(name !in registered) { "${javaClass.name} already has a test named \"$name\"; a name is its ID" }
        registered[name] = Registered(body, this.aspects + aspects + if (concurrent) Aspect.none else alone)
    }

    internal class Registered<R>(val body: (R) -> Unit, val aspects: Aspect)
}

/** Tests that share nothing. See [AnySuite]. */
@Suppress("AbstractClassCanBeConcreteClass") // a suite is an object that extends this, never an instance of it
abstract class Suite(
    limit: Limit = Limit.fixed(Runtime.getRuntime().availableProcessors()),
    concurrent: Boolean = true,
    aspects: Aspect = Aspect.none,
) : AnySuite<Unit>(limit, concurrent, aspects) {

    final override val toAcquire: Resource<Unit> = resource { }

    /** Registers a test that runs as its [aspects] say, inside the suite's. */
    protected fun test(name: String, aspects: Aspect = Aspect.none, body: () -> Unit) =
        register(name, aspects) { _ -> body() }
}

/** Tests that share one [R]. See [AnySuite]. */
abstract class SharedSuite<R>(
    limit: Limit = Limit.fixed(Runtime.getRuntime().availableProcessors()),
    concurrent: Boolean = true,
    aspects: Aspect = Aspect.none,
) : AnySuite<R>(limit, concurrent, aspects) {

    /**
     * Acquired once for the suite, handed to each test, and released after the last. If acquiring throws, each
     * test fails with that throw, and what was installed before it is released.
     */
    abstract val shared: Resource<R>

    final override val toAcquire: Resource<R> get() = shared

    /** Registers a test that runs as its [aspects] say, inside the suite's. */
    protected fun test(name: String, aspects: Aspect = Aspect.none, body: (R) -> Unit) =
        register(name, aspects, body)
}
