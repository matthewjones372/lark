package io.github.matthewjones372.surefold.junit

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldContain
import io.github.matthewjones372.surefold.assert.shouldContainExactlyInAnyOrder
import io.github.matthewjones372.surefold.assert.shouldStartWith
import io.github.matthewjones372.surefold.junit.samples.IgnoringSuite
import io.github.matthewjones372.surefold.junit.samples.MixedSuite
import io.github.matthewjones372.surefold.junit.samples.PartlyTaggedSuite
import io.github.matthewjones372.surefold.junit.samples.TaggedSuite
import io.github.matthewjones372.surefold.junit.samples.TimingSuite
import io.github.matthewjones372.surefold.junit.samples.UnacquirableSuite
import io.github.matthewjones372.surefold.junit.samples.WrappedSuite
import io.github.matthewjones372.surefold.junit.testkit.failures
import io.github.matthewjones372.surefold.junit.testkit.names
import io.github.matthewjones372.surefold.junit.testkit.shouldHaveFailed
import io.github.matthewjones372.surefold.junit.testkit.shouldHaveSkipped
import io.github.matthewjones372.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.Filter
import org.junit.platform.engine.TestTag
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.launcher.TagFilter.excludeTags
import org.junit.platform.launcher.TagFilter.includeTags
import org.junit.platform.testkit.engine.EngineTestKit
import org.junit.platform.testkit.engine.Events

class AspectEngineTest {

    private val tagged = arrayOf(TaggedSuite::class.java, PartlyTaggedSuite::class.java, MixedSuite::class.java)

    private fun run(vararg suites: Class<*>, filters: List<Filter<*>> = emptyList()): Events =
        EngineTestKit.engine("surefold").selectors(*suites.map { selectClass(it) }.toTypedArray())
            .filters(*filters.toTypedArray()).execute().testEvents()

    @Test
    fun `including a tag runs the tests tagged with it, and every test of a suite tagged with it`() {
        val started = run(*tagged, filters = listOf(includeTags("io"))).started().names()

        started shouldContainExactlyInAnyOrder listOf("reads the wire", "writes the wire", "tagged")
    }

    @Test
    fun `excluding a tag runs the rest`() {
        val started = run(*tagged, filters = listOf(excludeTags("io"))).started().names()

        started shouldContainExactlyInAnyOrder
            listOf("untagged", "passes", "fails an assertion", "throws something else")
    }

    @Test
    fun `a test carries its suite's tags and its own`() {
        val tags = run(PartlyTaggedSuite::class.java).started().list()
            .associate { it.testDescriptor.displayName to it.testDescriptor.tags }

        tags shouldBe mapOf(
            "tagged" to setOf(TestTag.create("suite"), TestTag.create("io")),
            "untagged" to setOf(TestTag.create("suite")),
        )
    }

    @Test
    fun `an ignored test is skipped with its reason and does not run`() {
        IgnoringSuite.ran.clear()

        val tests = run(IgnoringSuite::class.java)

        tests.skipped().list().associate {
            it.testDescriptor.displayName to it.getRequiredPayload(String::class.java)
        } shouldBe mapOf("speaks HTTP/3" to "no QUIC on CI", "ignored by the outer side" to "outer")
        tests shouldHaveSucceeded listOf("passes")
        IgnoringSuite.ran.toList() shouldBe listOf("passes")
    }

    @Test
    fun `an ignored test is skipped, not failed, when the suite's resource cannot be acquired`() {
        val tests = run(UnacquirableSuite::class.java)

        tests shouldHaveSkipped listOf("ignored never runs")
        tests shouldHaveFailed listOf("first never runs", "second never runs")
    }

    @Test
    fun `a suite's aspects wrap its test's, and the test's left aspect wraps its right`() {
        WrappedSuite.seen.clear()

        run(WrappedSuite::class.java).succeeded().count() shouldBe 1L

        WrappedSuite.seen.toList() shouldBe
            listOf("suite in", "left in", "right in", "test", "right out", "left out", "suite out")
    }

    @Test
    fun `timing aspects report a retried pass as passed with its attempts, and a failing run by its number`() {
        val events = EngineTestKit.engine("surefold").selectors(selectClass(TimingSuite::class.java)).execute()
        val tests = events.testEvents()
        val failures = tests.failures()
        val attempts = events.allEvents().reportingEntryPublished().list().associate {
            it.testDescriptor.displayName to it.getRequiredPayload(ReportEntry::class.java).keyValuePairs["attempts"]
        }

        tests shouldHaveSucceeded listOf("passes on its third attempt")
        attempts shouldBe mapOf("passes on its third attempt" to "3 of 3", "fails both of its attempts" to "2 of 2")
        failures.keys shouldBe setOf("fails both of its attempts", "fails one run in 10", "sleeps past its timeout")
        failures.getValue("fails one run in 10").message shouldStartWith "run 7 of 50 failed: "
        failures.getValue("sleeps past its timeout").message shouldContain "100ms"
    }
}
