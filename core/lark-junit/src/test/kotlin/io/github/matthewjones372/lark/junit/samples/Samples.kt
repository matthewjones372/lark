package io.github.matthewjones372.lark.junit.samples

import io.github.matthewjones372.lark.Limit
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.resource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

// Suites that fail on purpose. This module's own `test` task excludes this package, so only the
// tests that select them through EngineTestKit or a Launcher run them.

object MixedSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("passes") { }
        test("fails an assertion") { throw AssertionError("expected 1 but was 2") }
        test("throws something else") { error("the database was not there") }
    }
}

/** Records what each test was handed and when the resource was released, for the engine test to read. */
object OrderedSuite : Suite<String>(limit = Limit.fixed(1)) {
    val seen: MutableList<String> = mutableListOf()

    override val shared: Resource<String> = resource {
        install({ "db" }) { _, _ -> seen += "released" }
    }

    init {
        test("first") { db -> seen += "first $db" }
        test("second") { db -> seen += "second $db" }
        test("third") { db -> seen += "third $db" }
    }
}

/** Opens a connection, then fails to open the database, so the connection is all there is to release. */
object UnacquirableSuite : Suite<String>() {
    val released: MutableList<String> = CopyOnWriteArrayList()

    override val shared: Resource<String> = resource {
        install({ "connection" }) { connection, _ -> released += connection }
        error("no database")
    }

    init {
        test("first never runs") { }
        test("second never runs") { }
    }
}

/** Its tests pass, and closing what they shared throws. */
object UnreleasableSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource {
        install({ }) { _, _ -> error("could not close") }
    }

    init {
        test("passes first") { }
        test("passes second") { }
    }
}

/** Counts acquisitions, and records at release how many of its sixteen concurrent tests had finished. */
object CountedSuite : Suite<Unit>(limit = Limit.fixed(8)) {
    val acquired = AtomicInteger()
    val finished = AtomicInteger()

    @Volatile
    var finishedAtRelease: Int = -1

    override val shared: Resource<Unit> = resource {
        install({ acquired.incrementAndGet() }) { _, _ -> finishedAtRelease = finished.get() }
    }

    init {
        (1..16).forEach { n ->
            test("counted $n") {
                Thread.sleep(n * 5L)
                finished.incrementAndGet()
            }
        }
    }
}

/** Each test waits for all eight to be running, so the suite only finishes if they run together. */
object GatedSuite : Suite<CountDownLatch>(limit = Limit.fixed(8)) {
    val virtual: MutableMap<String, Boolean> = ConcurrentHashMap()

    override val shared: Resource<CountDownLatch> = resource { CountDownLatch(8) }

    init {
        (1..8).forEach { n ->
            test("gated $n") { gate ->
                virtual["gated $n"] = Thread.currentThread().isVirtual
                gate.countDown()
                check(gate.await(10, TimeUnit.SECONDS)) { "only ${8 - gate.count} of 8 tests ran together" }
            }
        }
    }
}

/** Would run all at once under its default limit, if `concurrent = false` were ignored. */
object SequentialSuite : Suite<Unit>(concurrent = false) {
    val seen: MutableList<String> = CopyOnWriteArrayList()

    override val shared: Resource<Unit> = resource { }

    init {
        (1..8).forEach { n -> test("in turn $n") { seen += "in turn $n" } }
    }
}

/** Two concurrent tests on either side of an `alone` one, which reads how many of its suite are running. */
object AloneSuite : Suite<Unit>(limit = Limit.fixed(8)) {
    private val running = AtomicInteger()
    val seen: MutableList<String> = CopyOnWriteArrayList()

    @Volatile
    var runningWhileAlone: Int = 0

    override val shared: Resource<Unit> = resource { }

    private fun together(name: String, gate: CountDownLatch) = test(name) {
        running.incrementAndGet()
        gate.countDown()
        check(gate.await(10, TimeUnit.SECONDS)) { "$name ran without its partner" }
        seen += name
        running.decrementAndGet()
    }

    init {
        val before = CountDownLatch(2)
        together("before a", before)
        together("before b", before)
        test("alone", alone = true) {
            runningWhileAlone = running.incrementAndGet()
            seen += "alone"
            running.decrementAndGet()
        }
        val after = CountDownLatch(2)
        together("after a", after)
        together("after b", after)
    }
}

/** A failing test between two that wait for each other, so it fails while both are running. */
object FailingSiblingSuite : Suite<CountDownLatch>(limit = Limit.fixed(3)) {
    override val shared: Resource<CountDownLatch> = resource { CountDownLatch(3) }

    init {
        test("waits first") { gate -> gate.countDown().also { check(gate.await(10, TimeUnit.SECONDS)) } }
        test("fails") { gate -> gate.countDown().also { error("failed alone") } }
        test("waits second") { gate -> gate.countDown().also { check(gate.await(10, TimeUnit.SECONDS)) } }
    }
}

/** Has a suite's shape but is a class, so there is no one instance to run. */
class NotAnObject : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("never found") { }
    }
}
