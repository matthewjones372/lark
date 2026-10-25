package io.github.matthewjones372.lark.openfeature

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Metadata
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.Value
import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.capturingMetrics
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldHaveSize
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource

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
object FeatureHookSuite : Suite<OpenFeatureAPI>() {
    override val shared: Resource<OpenFeatureAPI> = resource {
        OpenFeatureAPI.getInstance().apply {
            useLark()
            setProviderAndWait(Echo)
            setProviderAndWait("down", Down)
        }
    }

    init {
        test(
            "an evaluation against a failing provider warns once, counts one error, and answers with the default",
        ) { api ->
            val (answered, lines) = capturingLogs { logs ->
                capturingMetrics { measured ->
                    val answer = api.getClient("down").getBooleanValue("adoption.registry-check", true)
                    val counted = withClue("one reading, tagged with the flag and the error code") {
                        (measured.counter("openfeature.errors") shouldBe 1.0) and
                            (
                                measured.tags("openfeature.errors") shouldBe
                                    mapOf("flag" to "adoption.registry-check", "code" to "GENERAL")
                                )
                    }
                    answer to counted
                } to logs.all().filter { it.level == LogLevel.Warn }
            }
            val (answer, counted) = answered

            counted and
                (answer shouldBe true) and
                (lines shouldHaveSize 1) and
                (lines.single().message shouldContain "adoption.registry-check") and
                (lines.single().message shouldContain "GENERAL")
        }

        test("every evaluation is counted under its flag, variant and reason") { api ->
            capturingMetrics { measured ->
                val _ = evaluatedFor("ada") { api.client.getStringValue("who", "nobody") }

                (measured.counter("openfeature.evaluations") shouldBe 1.0) and
                    (measured.tags("openfeature.evaluations").keys shouldBe setOf("flag", "variant", "reason")) and
                    (measured.tags("openfeature.evaluations")["flag"] shouldBe "who")
            }
        }

        test("an evaluation that answered writes no warning and counts no error") { api ->
            capturingLogs { logs ->
                val counted = capturingMetrics { measured ->
                    val _ = api.client.getStringValue("who", "nobody")
                    measured.counter("openfeature.errors") shouldBe 0.0
                }
                counted and (logs.all().filter { it.level == LogLevel.Warn } shouldHaveSize 0)
            }
        }

        test("useLark called again adds the hook once") { api ->
            api.useLark()
            api.useLark()

            api.hooks.count { it === LarkFeatureHook } shouldBe 1
        }
    }
}
