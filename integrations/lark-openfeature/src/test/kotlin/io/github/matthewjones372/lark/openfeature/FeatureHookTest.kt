package io.github.matthewjones372.lark.openfeature

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Metadata
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.Value
import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldContain
import io.github.matthewjones372.lark.assert.shouldHaveSize
import io.github.matthewjones372.lark.assert.withClue
import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.capturingMetrics
import org.junit.jupiter.api.Test

/** A provider that is down: every evaluation throws. */
private object Down : FeatureProvider {
    override fun getMetadata() = Metadata { "down" }

    override fun getBooleanEvaluation(key: String, defaultValue: Boolean, ctx: EvaluationContext) = out<Boolean>()

    override fun getStringEvaluation(key: String, defaultValue: String, ctx: EvaluationContext) = out<String>()

    override fun getIntegerEvaluation(key: String, defaultValue: Int, ctx: EvaluationContext) = out<Int>()

    override fun getDoubleEvaluation(key: String, defaultValue: Double, ctx: EvaluationContext) = out<Double>()

    override fun getObjectEvaluation(key: String, defaultValue: Value, ctx: EvaluationContext) = out<Value>()

    private fun <A> out(): ProviderEvaluation<A> = error("the flag service is down")
}

/** Spec 0180: an evaluation is counted, and one that fell back to its default says so. */
class FeatureHookTest {

    private val api = OpenFeatureAPI.getInstance().apply {
        useLark()
        setProviderAndWait(Echo)
        setProviderAndWait("down", Down)
    }

    @Test
    fun `an evaluation against a failing provider warns once, counts one error, and answers with the default`() {
        val (answer, lines) = capturingLogs { logs ->
            capturingMetrics { measured ->
                val answer = api.getClient("down").getBooleanValue("adoption.registry-check", true)
                withClue("one reading, tagged with the flag and the error code") {
                    measured.counter("openfeature.errors") shouldBe 1.0
                    measured.tags("openfeature.errors") shouldBe
                        mapOf("flag" to "adoption.registry-check", "code" to "GENERAL")
                }
                answer
            } to logs.all().filter { it.level == LogLevel.Warn }
        }

        answer shouldBe true
        lines shouldHaveSize 1
        lines.single().message shouldContain "adoption.registry-check"
        lines.single().message shouldContain "GENERAL"
    }

    @Test
    fun `every evaluation is counted under its flag, variant and reason`() {
        capturingMetrics { measured ->
            evaluatedFor("ada") { api.client.getStringValue("who", "nobody") }

            measured.counter("openfeature.evaluations") shouldBe 1.0
            measured.tags("openfeature.evaluations").keys shouldBe setOf("flag", "variant", "reason")
            measured.tags("openfeature.evaluations")["flag"] shouldBe "who"
        }
    }

    @Test
    fun `an evaluation that answered writes no warning and counts no error`() {
        capturingLogs { logs ->
            capturingMetrics { measured ->
                api.client.getStringValue("who", "nobody")
                measured.counter("openfeature.errors") shouldBe 0.0
            }
            logs.all().filter { it.level == LogLevel.Warn } shouldHaveSize 0
        }
    }

    @Test
    fun `useLark called again adds the hook once`() {
        api.useLark()
        api.useLark()

        api.hooks.count { it === LarkFeatureHook } shouldBe 1
    }
}
