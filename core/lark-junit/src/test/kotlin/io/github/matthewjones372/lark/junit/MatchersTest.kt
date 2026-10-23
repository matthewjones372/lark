package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.junit.samples.MatchingSuite
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError

/** The value-returning matchers fail as lark-assert's throwing ones do, and combine as `expect` does. */
class MatchersTest {

    private val events by lazy {
        EngineTestKit.engine("lark").selectors(selectClass(MatchingSuite::class.java)).execute().testEvents()
    }

    private val failures: Map<String, Throwable> by lazy {
        events.failed().list().associate {
            it.testDescriptor.displayName to it.getRequiredPayload(TestExecutionResult::class.java).throwable.get()
        }
    }

    @Test
    fun `matchers pass and fail as their names say, alone and combined`() {
        events.succeeded().list().map { it.testDescriptor.displayName } shouldContainExactlyInAnyOrder listOf(
            "passes shouldBe and shouldNotBe",
            "passes either matcher",
            "passes shouldBeRight and shouldBeLeft",
        )
        failures.mapValues { (_, thrown) -> thrown::class } shouldContainExactly mapOf(
            "fails shouldBe" to AssertionFailedError::class,
            "fails shouldNotBe" to AssertionFailedError::class,
            "fails both matchers" to MultipleFailuresError::class,
            "fails shouldBeRight" to AssertionFailedError::class,
        )
    }

    @Test
    fun `a failed shouldBe carries expected and actual, so the IDE opens its diff`() {
        val failed = failures.getValue("fails shouldBe").shouldBeInstanceOf<AssertionFailedError>()

        failed.expected.value shouldBe 3
        failed.actual.value shouldBe 2
        failed.message.orEmpty().lines() shouldBe listOf(
            "fails shouldBe: 1 of 1 expectations failed",
            "expected: 3",
            "but was:  2",
            "at Samples.kt:${failed.stackTrace.first().lineNumber}",
            "    test(\"fails shouldBe\") { two -> two shouldBe 3 }",
        )
    }

    @Test
    fun `a failed shouldNotBe names the value it should not have been`() {
        failures.getValue("fails shouldNotBe").message.orEmpty().lines().take(3) shouldBe listOf(
            "fails shouldNotBe: 1 of 1 expectations failed",
            "expected: not 2",
            "but was:  2",
        )
    }

    @Test
    fun `two failed matchers joined by and are both reported, under a count of the checks`() {
        val failed = failures.getValue("fails both matchers").shouldBeInstanceOf<MultipleFailuresError>()

        failed.message.orEmpty().lines().first() shouldBe "fails both matchers: 2 of 2 expectations failed (2 failures)"
        failed.failures.map { it.message.orEmpty().lines().first() } shouldBe listOf("expected: 3", "expected: not 2")
    }

    @Test
    fun `a failed shouldBeRight carries the Right it wanted and the Either it found`() {
        val failed = failures.getValue("fails shouldBeRight").shouldBeInstanceOf<AssertionFailedError>()

        // opentest4j keeps only a serializable value, and an Either is not; its text is what the diff shows.
        failed.expected.stringRepresentation shouldBe Either.Right(2).toString()
        failed.actual.stringRepresentation shouldBe Either.Left(2).toString()
    }
}
