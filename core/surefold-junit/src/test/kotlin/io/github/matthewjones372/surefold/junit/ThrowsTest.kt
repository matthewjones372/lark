package io.github.matthewjones372.surefold.junit

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeInstanceOf
import io.github.matthewjones372.surefold.assert.shouldContainExactly
import io.github.matthewjones372.surefold.assert.shouldContainExactlyInAnyOrder
import io.github.matthewjones372.surefold.junit.samples.ThrowingSuite
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.AssertionFailedError

/** shouldThrow holds on a matching throw, fails on any other outcome, and lets a fatal throwable go on up. */
class ThrowsTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(ThrowingSuite::class.java)).execute().testEvents()
    }

    private val failures: Map<String, Throwable> by lazy {
        events.failed().list().associate {
            it.testDescriptor.displayName to it.getRequiredPayload(TestExecutionResult::class.java).throwable.get()
        }
    }

    @Test
    fun `a matching throw passes, and every other outcome fails`() {
        events.succeeded().list().map { it.testDescriptor.displayName } shouldContainExactlyInAnyOrder listOf(
            "passes a matching throw",
            "passes a matching throw, then checks it",
        )
        failures.mapValues { (_, thrown) -> thrown::class } shouldContainExactly mapOf(
            "fails a missing throw" to AssertionFailedError::class,
            "fails a wrong type" to AssertionFailedError::class,
            "fails the second lambda's check" to AssertionFailedError::class,
            "rethrows an interrupt" to InterruptedException::class,
        )
    }

    @Test
    fun `a missing throw names the type it wanted and this line`() {
        val failed = failures.getValue("fails a missing throw")

        failed.message.orEmpty().lines().take(4) shouldBe listOf(
            "fails a missing throw: 1 of 1 expectations failed",
            "expected: a java.lang.IllegalStateException thrown",
            "but was:  nothing thrown",
            "at Samples.kt:${failed.stackTrace.first().lineNumber}",
        )
    }

    @Test
    fun `a wrong type is a failure carrying what was thrown, and the second lambda does not run`() {
        val failed = failures.getValue("fails a wrong type").shouldBeInstanceOf<AssertionFailedError>()

        failed.cause.shouldBeInstanceOf<UnsupportedOperationException>().message shouldBe "other"
        failed.message.orEmpty().lines()[2] shouldBe "but was:  java.lang.UnsupportedOperationException: other"
        ThrowingSuite.thenRan.get() shouldBe 0
    }

    @Test
    fun `the second lambda's failure counts with the throw check`() {
        failures.getValue("fails the second lambda's check").message.orEmpty().lines().take(3) shouldBe listOf(
            "fails the second lambda's check: 1 of 2 expectations failed",
            "expected: two",
            "but was:  three",
        )
    }
}
