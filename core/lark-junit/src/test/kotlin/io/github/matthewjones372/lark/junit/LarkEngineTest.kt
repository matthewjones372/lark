package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.samples.AloneSuite
import io.github.matthewjones372.lark.junit.samples.FailingSiblingSuite
import io.github.matthewjones372.lark.junit.samples.GatedSuite
import io.github.matthewjones372.lark.junit.samples.MixedSuite
import io.github.matthewjones372.lark.junit.samples.NotAnObject
import io.github.matthewjones372.lark.junit.samples.OrderedSuite
import io.github.matthewjones372.lark.junit.samples.SequentialSuite
import io.github.matthewjones372.lark.junit.samples.UnacquirableSuite
import io.github.matthewjones372.lark.resource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import org.junit.platform.testkit.engine.EventType
import org.junit.platform.testkit.engine.Events

class LarkEngineTest {

    private fun run(selector: DiscoverySelector): EngineExecutionResults =
        EngineTestKit.engine("lark").selectors(selector).execute()

    private fun Events.names(): List<String> = map { it.testDescriptor.displayName }.toList()

    @Test
    fun `a pass, an assertion and a throw are reported under the tests' names`() {
        val tests = run(selectClass(MixedSuite::class.java)).testEvents()

        tests.succeeded().names() shouldBe listOf("passes")
        tests.failed().list().associate {
            it.testDescriptor.displayName to
                it.getRequiredPayload(TestExecutionResult::class.java).throwable.get()::class
        } shouldContainExactly mapOf(
            "fails an assertion" to AssertionError::class,
            "throws something else" to IllegalStateException::class,
        )
    }

    @Test
    fun `a test's unique ID is its suite's class and its name`() {
        val started = run(selectClass(MixedSuite::class.java)).testEvents().started().list().first()

        started.testDescriptor.uniqueId.toString() shouldBe
            "[engine:lark]/[suite:${MixedSuite::class.java.name}]/[test:passes]"
    }

    @Test
    fun `under a limit of one, tests run in the order they were registered, with the resource acquired once`() {
        OrderedSuite.seen.clear()

        run(selectClass(OrderedSuite::class.java)).testEvents().succeeded().count() shouldBe 3L

        OrderedSuite.seen shouldBe listOf("first db", "second db", "third db", "released")
    }

    @Test
    fun `eight tests that wait for each other all finish under a limit of eight, each on a virtual thread`() {
        GatedSuite.virtual.clear()

        val tests = run(selectClass(GatedSuite::class.java)).testEvents()

        tests.succeeded().count() shouldBe 8L
        GatedSuite.virtual shouldContainExactly (1..8).associate { "gated $it" to true }
    }

    @Test
    fun `a suite that is not concurrent runs its tests one at a time, in order`() {
        SequentialSuite.seen.clear()

        run(selectClass(SequentialSuite::class.java)).testEvents().succeeded().count() shouldBe 8L

        SequentialSuite.seen shouldBe (1..8).map { "in turn $it" }
    }

    @Test
    fun `an alone test runs with nothing else in its suite running, after the tests before it`() {
        AloneSuite.seen.clear()

        run(selectClass(AloneSuite::class.java)).testEvents().succeeded().count() shouldBe 5L

        withClue("tests of the suite running while the alone test ran, counting itself") {
            AloneSuite.runningWhileAlone shouldBe 1
        }
        AloneSuite.seen.toList().subList(0, 2).sorted() shouldBe listOf("before a", "before b")
        AloneSuite.seen[2] shouldBe "alone"
    }

    @Test
    fun `a failing test leaves its siblings running and its suite passing`() {
        val events = run(selectClass(FailingSiblingSuite::class.java))

        events.testEvents().succeeded().names().sorted() shouldBe listOf("waits first", "waits second")
        events.testEvents().failed().names() shouldBe listOf("fails")
        events.containerEvents().succeeded().names() shouldBe listOf("FailingSiblingSuite", "Lark")
    }

    @Test
    fun `every started test is finished before its suite is`() {
        val events = run(selectClass(GatedSuite::class.java)).allEvents().list()
        val suiteFinished = events.indexOfLast { it.testDescriptor.displayName == "GatedSuite" }

        events.count { it.type == EventType.STARTED && it.testDescriptor.isTest } shouldBe 8
        events.withIndex().filter { (_, e) -> e.type == EventType.FINISHED && e.testDescriptor.isTest }
            .map { it.index }.all { it < suiteFinished } shouldBe true
    }

    @Test
    fun `a suite whose resource cannot be acquired fails, and none of its tests start`() {
        val events = run(selectClass(UnacquirableSuite::class.java))

        events.testEvents().started().count() shouldBe 0L
        events.containerEvents().failed().names() shouldBe listOf(UnacquirableSuite::class.java.simpleName)
    }

    @Test
    fun `a package selector finds the suite objects in it and passes over a suite class`() {
        val suites = run(selectPackage(MixedSuite::class.java.packageName)).containerEvents().started().names()

        suites.sorted() shouldBe listOf(
            "AloneSuite",
            "FailingSiblingSuite",
            "GatedSuite",
            "Lark",
            "MixedSuite",
            "OrderedSuite",
            "SequentialSuite",
            "UnacquirableSuite",
        )
        run(selectClass(NotAnObject::class.java)).testEvents().started().count() shouldBe 0L
    }

    @Test
    fun `two tests with one name are refused, because the name is the test's ID`() {
        val refused = shouldThrow<IllegalArgumentException> {
            object : Suite<Unit>() {
                override val shared: Resource<Unit> = resource { }

                init {
                    test("twice") { }
                    test("twice") { }
                }
            }
        }

        refused.message.orEmpty() shouldContain "\"twice\""
    }
}
