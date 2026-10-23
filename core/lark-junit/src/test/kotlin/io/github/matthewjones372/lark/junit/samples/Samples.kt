package io.github.matthewjones372.lark.junit.samples

import io.github.matthewjones372.lark.Limit
import io.github.matthewjones372.lark.Reading
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.junit.Expectations
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeLeft
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.junit.shouldNotBe
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.logWarn
import io.github.matthewjones372.lark.readings
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
        test("passes") { expect(true) }
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
        test("first") { db -> expect(seen.add("first $db")) }
        test("second") { db -> expect(seen.add("second $db")) }
        test("third") { db -> expect(seen.add("third $db")) }
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
        test("first never runs") { expect(true) }
        test("second never runs") { expect(true) }
    }
}

/** Its tests pass, and closing what they shared throws. */
object UnreleasableSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource {
        install({ }) { _, _ -> error("could not close") }
    }

    init {
        test("passes first") { expect(true) }
        test("passes second") { expect(true) }
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
                expect(finished.incrementAndGet() > 0)
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
                expect(gate.await(10, TimeUnit.SECONDS))
            }
        }
    }
}

/** Would run all at once under its default limit, if `concurrent = false` were ignored. */
object SequentialSuite : Suite<Unit>(concurrent = false) {
    val seen: MutableList<String> = CopyOnWriteArrayList()

    override val shared: Resource<Unit> = resource { }

    init {
        (1..8).forEach { n -> test("in turn $n") { expect(seen.add("in turn $n")) } }
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
        expect(running.decrementAndGet() >= 0)
    }

    init {
        val before = CountDownLatch(2)
        together("before a", before)
        together("before b", before)
        test("alone", alone = true) {
            runningWhileAlone = running.incrementAndGet()
            seen += "alone"
            expect(running.decrementAndGet() == 0)
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
        test("waits first") { gate ->
            gate.countDown()
            expect(gate.await(10, TimeUnit.SECONDS))
        }
        test("fails") { gate ->
            gate.countDown()
            error("failed alone")
        }
        test("waits second") { gate ->
            gate.countDown()
            expect(gate.await(10, TimeUnit.SECONDS))
        }
    }
}

/** Two tests that log while both are running and then fail, a passing one that logs, and one whose fork logs. */
object LoggingSuite : Suite<CountDownLatch>(limit = Limit.fixed(4)) {
    override val shared: Resource<CountDownLatch> = resource { CountDownLatch(2) }

    private fun failsTogether(name: String) = test(name) { gate ->
        logInfo("$name started")
        gate.countDown()
        check(gate.await(10, TimeUnit.SECONDS)) { "$name ran without its partner" }
        logWarn("$name is about to fail")
        throw AssertionError("$name failed")
    }

    init {
        failsTogether("fails first")
        failsTogether("fails second")
        test("passes quietly") {
            logInfo("nobody should see this")
            expect(true)
        }
        test("fails after its fork logs") {
            flock<Nothing, Unit> { async { logInfo("logged from a fork") } }
            error("the fork's line is mine")
        }
    }
}

/** Two tests that count the same metric while both are running, and record the total each one read. */
object MeteredSuite : Suite<CountDownLatch>(limit = Limit.fixed(2)) {
    val totals: MutableMap<String, Double> = ConcurrentHashMap()

    override val shared: Resource<CountDownLatch> = resource { CountDownLatch(2) }

    private fun counts(name: String, times: Int) = test(name) { gate ->
        repeat(times) { counter("hits").increment() }
        gate.countDown()
        check(gate.await(10, TimeUnit.SECONDS)) { "$name ran without its partner" }
        totals[name] = readings("hits").filterIsInstance<Reading.Total>().sumOf { it.total }
        expect(true)
    }

    init {
        counts("counts one", 1)
        counts("counts two", 2)
    }
}

/** Has a suite's shape but is a class, so there is no one instance to run. */
class NotAnObject : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("never found") { expect(true) }
    }
}

/** Expectations combined every way, each test named for how it should end. */
object ExpectingSuite : Suite<Int>() {
    override val shared: Resource<Int> = resource { 2 }

    // Power-assert rewrites calls, not references, so this is `expect` as a caller built without the plugin sees it.
    private val unexplained: (Boolean) -> Expectations = ::expect

    init {
        test("passes both") { two -> expect(two == 2) and expect(two + two == 4) }
        test("fails both") { two ->
            expect(two == 3) and
                expect(two + two == 5)
        }
        test("fails one of two") { two -> expect(two == 2) and expect(two == 3) }
        test("passes either") { two -> expect(two == 3) or expect(two == 2) }
        test("fails either") { two -> expect(two == 3) or expect(two == 4) }
        test("fails a negated pass") { two -> !expect(two == 2) }
        test("passes a negated failure") { two -> !expect(two == 3) }
        test("throws before it expects") { error("no expectations reached") }
        test("fails unexplained") { two -> unexplained(two == 3) }
    }
}

/** lark-junit's value-returning matchers, each test named for how it should end. */
object MatchingSuite : Suite<Int>() {
    override val shared: Resource<Int> = resource { 2 }

    init {
        test("passes shouldBe and shouldNotBe") { two -> (two shouldBe 2) and (two shouldNotBe 3) }
        test("fails shouldBe") { two -> two shouldBe 3 }
        test("fails shouldNotBe") { two -> two shouldNotBe 2 }
        test("fails both matchers") { two -> (two shouldBe 3) and (two shouldNotBe 2) }
        test("passes either matcher") { two -> (two shouldBe 3) or (two shouldBe 2) }
        test("passes shouldBeRight and shouldBeLeft") { two ->
            (Either.Right(two) shouldBeRight 2) and (Either.Left(two) shouldBeLeft 2)
        }
        test("fails shouldBeRight") { two -> Either.Left(two) shouldBeRight 2 }
    }
}
