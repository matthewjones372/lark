package io.github.matthewjones372.surefold.junit

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldContain
import io.github.matthewjones372.surefold.assert.shouldContainExactly
import io.github.matthewjones372.surefold.assert.shouldContainExactlyInAnyOrder
import io.github.matthewjones372.surefold.assert.shouldNotContain
import io.github.matthewjones372.surefold.assert.shouldThrow
import io.github.matthewjones372.surefold.assert.withClue
import io.github.matthewjones372.surefold.junit.samples.AloneSuite
import io.github.matthewjones372.surefold.junit.samples.CountedSuite
import io.github.matthewjones372.surefold.junit.samples.ExpectingSuite
import io.github.matthewjones372.surefold.junit.samples.FailingSiblingSuite
import io.github.matthewjones372.surefold.junit.samples.GatedSuite
import io.github.matthewjones372.surefold.junit.samples.LoggingSuite
import io.github.matthewjones372.surefold.junit.samples.MeteredSuite
import io.github.matthewjones372.surefold.junit.samples.MixedSuite
import io.github.matthewjones372.surefold.junit.samples.NotAnObject
import io.github.matthewjones372.surefold.junit.samples.OrderedSuite
import io.github.matthewjones372.surefold.junit.samples.SequentialSuite
import io.github.matthewjones372.surefold.junit.samples.UnacquirableSuite
import io.github.matthewjones372.surefold.junit.samples.UnreleasableSuite
import org.junit.jupiter.api.Test
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage
import org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import org.junit.platform.testkit.engine.EventType
import org.junit.platform.testkit.engine.Events
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class SurefoldEngineTest {

    private fun run(selector: DiscoverySelector): EngineExecutionResults =
        EngineTestKit.engine("surefold").selectors(selector).execute()

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
            "[engine:surefold]/[suite:${MixedSuite::class.java.name}]/[test:passes]"
    }

    @Test
    fun `a test selected by its unique ID runs alone, as an IDE reruns one`() {
        val id = "[engine:surefold]/[suite:${MixedSuite::class.java.name}]/[test:fails an assertion]"

        run(selectUniqueId(id)).testEvents().started().names() shouldBe listOf("fails an assertion")
    }

    @Test
    fun `a suite selected by its unique ID runs all its tests`() {
        val events = run(selectUniqueId("[engine:surefold]/[suite:${MixedSuite::class.java.name}]")).testEvents()

        events.started().names() shouldContainExactlyInAnyOrder
            listOf("passes", "fails an assertion", "throws something else")
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
        events.containerEvents().succeeded().names() shouldBe listOf("FailingSiblingSuite", "Surefold")
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
    fun `the shared resource is acquired once for many concurrent tests and released after the last finishes`() {
        CountedSuite.acquired.set(0)
        CountedSuite.finished.set(0)

        run(selectClass(CountedSuite::class.java)).testEvents().succeeded().count() shouldBe 16L

        CountedSuite.acquired.get() shouldBe 1
        withClue("tests finished when the resource was released") { CountedSuite.finishedAtRelease shouldBe 16 }
    }

    @Test
    fun `a failed acquisition fails each test with its cause, passes the suite and releases what it installed`() {
        UnacquirableSuite.released.clear()

        val events = run(selectClass(UnacquirableSuite::class.java))

        val failed = events.testEvents().failed().list()
        // The suite's tests run concurrently, so they finish, and are reported, in either order.
        failed.map { it.testDescriptor.displayName }.sorted() shouldBe listOf("first never runs", "second never runs")
        val causes = failed.map { it.getRequiredPayload(TestExecutionResult::class.java).throwable.get() }
        withClue("one throwable, handed to every test") { causes.toSet().size shouldBe 1 }
        causes.first().message shouldBe "no database"
        events.containerEvents().succeeded().names() shouldBe listOf("UnacquirableSuite", "Surefold")
        UnacquirableSuite.released shouldBe listOf("connection")
    }

    @Test
    fun `a release that throws fails the suite and leaves its tests' results as they were`() {
        val events = run(selectClass(UnreleasableSuite::class.java))

        events.testEvents().succeeded().names().sorted() shouldBe listOf("passes first", "passes second")
        val suite = events.containerEvents().failed().list().single()
        suite.testDescriptor.displayName shouldBe "UnreleasableSuite"
        suite.getRequiredPayload(TestExecutionResult::class.java).throwable.get().message shouldBe "could not close"
    }

    @Test
    fun `a package selector finds the suite objects in it and passes over a suite class`() {
        val suites = run(selectPackage(MixedSuite::class.java.packageName)).containerEvents().started().names()

        suites.sorted() shouldBe listOf(
            "AloneSuite",
            "CluedSuite",
            "ContainingSuite",
            "CountedSuite",
            "DefaultTimeoutSuite",
            "ExpectingSuite",
            "FailingSiblingSuite",
            "ForAllSuite",
            "GappedSuite",
            "GatedSuite",
            "HoldsSuite",
            "IgnoringSuite",
            "LoggingSuite",
            "MatchingSuite",
            "MeteredSuite",
            "MixedSuite",
            "OrderSuite",
            "OrderedSuite",
            "PartlyTaggedSuite",
            "RecordedSuite",
            "SequentialSuite",
            "StorySuite",
            "Surefold",
            "TaggedSuite",
            "ThrowingSuite",
            "TimingSuite",
            "UnacquirableSuite",
            "UnreleasableSuite",
            "WaitSuite",
            "WrappedSuite",
        )
        run(selectClass(NotAnObject::class.java)).testEvents().started().count() shouldBe 0L
    }

    private fun logsOf(events: EngineExecutionResults): Map<String, List<String>> =
        events.allEvents().reportingEntryPublished().list().groupBy(
            { it.testDescriptor.displayName },
            { it.getRequiredPayload(ReportEntry::class.java).keyValuePairs.getValue("logs") },
        )

    @Test
    fun `a failing test's report entry holds its own log lines and none of a sibling's running beside it`() {
        val logs = logsOf(run(selectClass(LoggingSuite::class.java)))

        logs.getValue("fails first").single().lines() shouldBe listOf(
            "INFO ${LoggingSuite::class.java.name} fails first started",
            "WARN ${LoggingSuite::class.java.name} fails first is about to fail",
        )
        logs.getValue("fails second").single() shouldContain "fails second is about to fail"
        logs.getValue("fails second").single() shouldNotContain "fails first"
    }

    @Test
    fun `a passing test publishes no report entry`() {
        logsOf(run(selectClass(LoggingSuite::class.java))).keys shouldNotContain "passes quietly"
    }

    @Test
    fun `a line logged from a fork the test opened is the test's`() {
        logsOf(run(selectClass(LoggingSuite::class.java))).getValue("fails after its fork logs").single() shouldBe
            "INFO ${LoggingSuite::class.java.name} logged from a fork"
    }

    @Test
    fun `tests failed by a failed acquisition ran nothing and publish no report entry`() {
        logsOf(run(selectClass(UnacquirableSuite::class.java))) shouldBe emptyMap()
    }

    @Test
    fun `a failing test's lines go to stdout, and a passing test's do not`() {
        val captured = ByteArrayOutputStream()
        val stdout = System.out
        System.setOut(PrintStream(captured, true))
        try {
            run(selectClass(LoggingSuite::class.java))
        } finally {
            System.setOut(stdout)
        }

        val printed = captured.toString()
        printed shouldContain "fails first is about to fail"
        printed shouldContain "logged from a fork"
        printed shouldNotContain "nobody should see this"
    }

    @Test
    fun `two tests running together count into metrics of their own`() {
        MeteredSuite.totals.clear()

        run(selectClass(MeteredSuite::class.java)).testEvents().succeeded().count() shouldBe 2L

        MeteredSuite.totals shouldContainExactly mapOf("counts one" to 1.0, "counts two" to 2.0)
    }

    private fun failuresOf(events: EngineExecutionResults): Map<String, Throwable> =
        events.testEvents().failed().list().associate {
            it.testDescriptor.displayName to it.getRequiredPayload(TestExecutionResult::class.java).throwable.get()
        }

    @Test
    fun `two failing expectations are both reported, each at its own line, as one MultipleFailuresError`() {
        val failed = failuresOf(run(selectClass(ExpectingSuite::class.java))).getValue("fails both")

        val both = (failed as MultipleFailuresError).failures
        both.map { it::class } shouldBe listOf(AssertionFailedError::class, AssertionFailedError::class)
        both.map { it.message.orEmpty().lines().first() } shouldBe listOf(
            "expect(two == 3)",
            "expect(two + two == 5)",
        )
        both.map { it.stackTrace.first().fileName } shouldBe listOf("Samples.kt", "Samples.kt")
        withClue("the second failure is on the line after the first") {
            both[1].stackTrace.first().lineNumber shouldBe both[0].stackTrace.first().lineNumber + 1
        }
        failed.message.orEmpty() shouldContain "fails both: 2 of 2 expectations failed"
    }

    @Test
    fun `and, or and not pass and fail as their names say`() {
        val events = run(selectClass(ExpectingSuite::class.java))

        events.testEvents().succeeded().names() shouldContainExactlyInAnyOrder
            listOf("passes both", "passes either", "passes a negated failure")
        failuresOf(events).mapValues { (_, thrown) -> thrown::class } shouldContainExactly mapOf(
            "fails both" to MultipleFailuresError::class,
            "fails one of two" to AssertionFailedError::class,
            "fails either" to MultipleFailuresError::class,
            "fails a negated pass" to AssertionFailedError::class,
            "throws before it expects" to IllegalStateException::class,
            "fails unexplained" to AssertionFailedError::class,
        )
    }

    @Test
    fun `a single failed expectation is reported alone, under a heading that counts the expectations`() {
        val failed = failuresOf(run(selectClass(ExpectingSuite::class.java)))

        failed.getValue("fails one of two").message.orEmpty().lines().take(2) shouldBe listOf(
            "fails one of two: 1 of 2 expectations failed",
            "expect(two == 3)",
        )
        failed.getValue("fails a negated pass").message.orEmpty() shouldContain "not { expect(two == 2) }"
    }

    @Test
    fun `an expect called without power-assert says it was false and quotes the line that called it`() {
        val message = failuresOf(run(selectClass(ExpectingSuite::class.java))).getValue("fails unexplained").message

        message.orEmpty().lines().take(3) shouldBe listOf(
            "fails unexplained: 1 of 1 expectations failed",
            "expected: true",
            "but was:  false",
        )
        message.orEmpty().lines().last().trim() shouldBe
            "private val unexplained: (Boolean) -> Unit = ::expect"
    }

    @Test
    fun `two tests with one name are refused, because the name is the test's ID`() {
        val refused = shouldThrow<IllegalArgumentException> {
            object : Suite() {
                init {
                    test("twice") { expect(true) }
                    test("twice") { expect(true) }
                }
            }
        }

        refused.message.orEmpty() shouldContain "\"twice\""
    }
}
