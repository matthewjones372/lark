package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldContainExactlyInAnyOrder
import io.github.matthewjones372.lark.assert.shouldMatch
import io.github.matthewjones372.lark.junit.samples.ContainingSuite
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/** The contains and same-instance matchers fail as lark-assert's do, carrying the actual value and the test's line. */
class ContainsTest {

    private val events by lazy {
        EngineTestKit.engine("lark").selectors(selectClass(ContainingSuite::class.java)).execute().testEvents()
    }

    private val failures: Map<String, List<String>> by lazy {
        events.failed().list().associate {
            val thrown = it.getRequiredPayload(TestExecutionResult::class.java).throwable.get()
            it.testDescriptor.displayName to thrown.message.orEmpty().lines().drop(1)
        }
    }

    @Test
    fun `every matcher passes when it holds`() {
        events.succeeded().list().map { it.testDescriptor.displayName } shouldBe listOf("passes every matcher")
        failures.keys shouldContainExactlyInAnyOrder listOf(
            "fails a string shouldContain",
            "fails a null string",
            "fails a string shouldNotContain",
            "fails a collection shouldContain",
            "fails a collection shouldNotContain",
            "fails shouldBeSameInstanceAs",
            "fails shouldNotBeSameInstanceAs",
        )
    }

    @Test
    fun `a failed matcher says what it wanted and what it found, at the test's line`() {
        val wanted = mapOf(
            "fails a string shouldContain" to listOf("expected: a string containing \"w\"", "but was:  \"one\""),
            "fails a null string" to listOf("expected: a string containing \"w\"", "but was:  null"),
            "fails a string shouldNotContain" to listOf("expected: a string without \"n\"", "but was:  \"one\""),
            "fails a collection shouldContain" to
                listOf("expected: a collection containing three", "but was:  [one, two]"),
            "fails a collection shouldNotContain" to
                listOf("expected: a collection without one", "but was:  [one, two]"),
        )
        failures.filterKeys(wanted::containsKey).mapValues { (_, lines) -> lines.take(2) } shouldBe wanted
        failures.values.forEach { lines -> lines[2] shouldMatch Regex("at Samples\\.kt:\\d+") }
    }

    @Test
    fun `a failed instance check names both objects by identity`() {
        val (expected, actual) = failures.getValue("fails shouldBeSameInstanceAs")
        expected shouldMatch Regex("expected: \\[one, two] @[0-9a-f]+")
        actual shouldMatch Regex("but was:  \\[one, two] @[0-9a-f]+")
        failures.getValue("fails shouldNotBeSameInstanceAs")[0] shouldMatch
            Regex("expected: anything but \\[one, two] @[0-9a-f]+")
    }
}
