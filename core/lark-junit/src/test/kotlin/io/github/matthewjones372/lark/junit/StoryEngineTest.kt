package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeInstanceOf
import io.github.matthewjones372.lark.assert.shouldContain
import io.github.matthewjones372.lark.assert.shouldNotContain
import io.github.matthewjones372.lark.junit.samples.StorySuite
import io.github.matthewjones372.lark.test.StoryFailed
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class StoryEngineTest {

    private fun run(): Pair<EngineExecutionResults, String> {
        val printed = ByteArrayOutputStream()
        val stdout = System.out
        System.setOut(PrintStream(printed, true, Charsets.UTF_8))
        val results = try {
            EngineTestKit.engine("lark").selectors(selectClass(StorySuite::class.java)).execute()
        } finally {
            System.setOut(stdout)
        }
        return results to printed.toString(Charsets.UTF_8)
    }

    private fun logsOf(events: EngineExecutionResults): Map<String, String> =
        events.allEvents().reportingEntryPublished().list().associate {
            it.testDescriptor.displayName to
                it.getRequiredPayload(ReportEntry::class.java).keyValuePairs.getValue("logs")
        }

    private fun failuresOf(events: EngineExecutionResults): Map<String, Throwable> =
        events.testEvents().failed().list().associate {
            it.testDescriptor.displayName to it.getRequiredPayload(TestExecutionResult::class.java).throwable.get()
        }

    @Test
    fun `an untitled story is titled with its test's name, and a failing story's transcript is in its logs`() {
        val logs = logsOf(run().first)

        logs.getValue("fails two thens").lines().first() shouldBe "INFO Story: fails two thens"
        logs.getValue("throws in a step") shouldContain "Story: throws in a step"
        logs.getValue("throws in a step") shouldContain "✗ Given a step that throws"
    }

    @Test
    fun `a passing story prints nothing and publishes no report entry`() {
        val (events, printed) = run()

        events.testEvents().succeeded().list().map { it.testDescriptor.displayName } shouldBe listOf("passes its story")
        logsOf(events).keys shouldNotContain "passes its story"
        printed shouldNotContain "passes its story"
        printed shouldNotContain "a passing story doubles two"
    }

    @Test
    fun `two failing thens joined with and are both reported, and both are marked failed in the transcript`() {
        val (events, _) = run()

        val both = failuresOf(events).getValue("fails two thens").shouldBeInstanceOf<MultipleFailuresError>()
        both.failures.map { it.message.orEmpty().lines().first() } shouldBe listOf("expected: 3", "expected: 4")
        val transcript = logsOf(events).getValue("fails two thens")
        transcript shouldContain "✓ Given two"
        transcript shouldContain "✗ Then it is three"
        transcript shouldContain "✗ Then it is four"
    }

    @Test
    fun `a story's failed then is reported alone, beside a then that passed`() {
        val (events, _) = run()

        failuresOf(events).getValue("fails one then of two").shouldBeInstanceOf<AssertionFailedError>()
        val transcript = logsOf(events).getValue("fails one then of two")
        transcript shouldContain "✓ Then it is two"
        transcript shouldContain "✗ Then it is five"
    }

    @Test
    fun `a step that throws fails its test with the story`() {
        failuresOf(run().first).getValue("throws in a step").shouldBeInstanceOf<StoryFailed>()
            .cause!!.message shouldBe "no database"
    }
}
