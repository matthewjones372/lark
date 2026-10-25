package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldContainExactlyInAnyOrder
import io.github.matthewjones372.lark.assert.shouldMatch
import io.github.matthewjones372.lark.junit.samples.GappedSuite
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/** Spec 0192's matchers fail as lark-assert's do, carrying the actual value and naming the test's line. */
class GappedMatchersTest {

    private val events by lazy {
        EngineTestKit.engine("lark").selectors(selectClass(GappedSuite::class.java)).execute().testEvents()
    }

    private val failures: Map<String, List<String>> by lazy {
        events.failed().list().associate {
            val thrown = it.getRequiredPayload(TestExecutionResult::class.java).throwable.get()
            it.testDescriptor.displayName to thrown.message.orEmpty().lines().drop(1)
        }
    }

    @Test
    fun `every matcher passes when it holds, and each fails when it does not`() {
        events.succeeded().list().map { it.testDescriptor.displayName } shouldBe listOf("passes every matcher")
        failures.keys shouldContainExactlyInAnyOrder listOf(
            "fails in any order",
            "fails shouldBeEmpty",
            "fails shouldNotBeEmpty on a map",
            "fails shouldNotBeNull",
            "fails shouldBeLessThan",
            "fails shouldBeGreaterThanOrEqual",
            "fails shouldContainAll",
            "fails shouldContainKey",
            "fails shouldNotContainKey",
            "fails shouldContain on a map",
            "fails shouldContainExactly",
            "fails shouldHaveSize",
            "fails shouldBeNull",
            "fails shouldBeInRange",
            "fails shouldContainAll on a map",
            "fails shouldBeRight",
            "fails shouldBeLeft",
        )
    }

    @Test
    fun `a failed matcher says what it wanted and what it found, at the test's line`() {
        failures.mapValues { (_, lines) -> lines.take(2) } shouldBe mapOf(
            "fails in any order" to listOf("expected: [1, 2, 3] in any order", "but was:  [1, 2, 2, 3]"),
            "fails shouldBeEmpty" to listOf("expected: no elements", "but was:  [1, 2, 2, 3]"),
            "fails shouldNotBeEmpty on a map" to listOf("expected: at least one entry", "but was:  {}"),
            "fails shouldNotBeNull" to listOf("expected: a value, not null", "but was:  null"),
            "fails shouldBeLessThan" to listOf("expected: less than 2", "but was:  3"),
            "fails shouldBeGreaterThanOrEqual" to listOf("expected: at least 2", "but was:  1"),
            "fails shouldContainAll" to listOf("expected: all of [3, 4]", "but was:  [1, 2, 2, 3], missing [4]"),
            "fails shouldContainKey" to listOf("expected: a map with the key b", "but was:  {a=1}"),
            "fails shouldNotContainKey" to listOf("expected: a map without the key a", "but was:  {a=1}"),
            "fails shouldContain on a map" to listOf("expected: a map with a=2", "but was:  {a=1}"),
            "fails shouldContainExactly" to listOf("expected: [1, 2, 3]", "but was:  [1, 2, 2, 3]"),
            "fails shouldHaveSize" to listOf("expected: 3 elements", "but was:  4 in [1, 2, 2, 3]"),
            "fails shouldBeNull" to listOf("expected: null", "but was:  1"),
            "fails shouldBeInRange" to listOf("expected: in 1..3", "but was:  4"),
            "fails shouldContainAll on a map" to
                listOf("expected: all of {a=1, b=2}", "but was:  {a=1}, missing {b=2}"),
            "fails shouldBeRight" to listOf("expected: a Right", "but was:  Left(value=no)"),
            "fails shouldBeLeft" to listOf("expected: a Left", "but was:  Right(value=1)"),
        )
        failures.values.forEach { lines -> lines[2] shouldMatch Regex("at Samples\\.kt:\\d+") }
    }

    @Test
    fun `a miss does not run a narrowing matcher's lambda`() {
        failures.keys.containsAll(listOf("fails shouldNotBeNull", "fails shouldBeRight", "fails shouldBeLeft")) shouldBe
            true
        GappedSuite.thenRan.get() shouldBe 0
    }
}
