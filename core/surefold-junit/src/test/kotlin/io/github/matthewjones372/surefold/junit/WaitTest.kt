package io.github.matthewjones372.surefold.junit

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeInstanceOf
import io.github.matthewjones372.surefold.assert.shouldStartWith
import io.github.matthewjones372.surefold.junit.samples.WaitSuite
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.AssertionFailedError

/** eventually waits until a check holds, and gives up with the last try's failure. */
class WaitTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(WaitSuite::class.java)).execute().testEvents()
    }

    @Test
    fun `a check that holds on a later try passes, at the top level, as a step, and after a throw`() {
        events.succeeded().list().map { it.testDescriptor.displayName }.sorted() shouldBe listOf(
            "holds once its check does, on the third try",
            "tries again after a throw",
            "waits as a story's step",
        )
    }

    @Test
    fun `a check that never holds fails with its own failure, saying the wait gave up`() {
        val failed = events.failed().list().single()
            .getRequiredPayload(TestExecutionResult::class.java).throwable.get()
            .shouldBeInstanceOf<AssertionFailedError>()

        failed.message.orEmpty().lines()[1] shouldStartWith "gave up after "
        failed.expected.value shouldBe "Adopted"
        failed.actual.value shouldBe "Adopting"
    }
}
