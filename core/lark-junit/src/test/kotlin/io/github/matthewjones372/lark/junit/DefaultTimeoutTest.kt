package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldContain
import io.github.matthewjones372.lark.junit.samples.DefaultTimeoutSuite
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/** `lark.junit.timeout.default` holds a test with no timeout of its own to it, as Jupiter's default does. */
class DefaultTimeoutTest {

    private fun run(default: String) = EngineTestKit.engine("lark")
        .configurationParameter(DEFAULT_TIMEOUT, default)
        .selectors(selectClass(DefaultTimeoutSuite::class.java))
        .execute()
        .testEvents()

    @Test
    fun `a test with no timeout of its own fails at the default, and one with its own runs past it`() {
        val events = run("200ms")

        events.succeeded().list().map { it.testDescriptor.displayName } shouldBe
            listOf("outlasts the default under its own timeout")
        val failed = events.failed().list().single()
        failed.testDescriptor.displayName shouldBe "sleeps past the default"
        failed.getRequiredPayload(TestExecutionResult::class.java).throwable.get().toString() shouldContain "200ms"
    }

    @Test
    fun `a default that is not a duration fails the run, naming the parameter`() {
        val engine = EngineTestKit.engine("lark")
            .configurationParameter(DEFAULT_TIMEOUT, "soon")
            .selectors(selectClass(DefaultTimeoutSuite::class.java))
            .execute()
            .allEvents()

        val thrown = engine.failed().list().single().getRequiredPayload(TestExecutionResult::class.java).throwable.get()
        // The platform wraps what an engine's execute throws, naming the engine.
        thrown.cause?.message.orEmpty() shouldContain "lark.junit.timeout.default is soon"
    }
}
