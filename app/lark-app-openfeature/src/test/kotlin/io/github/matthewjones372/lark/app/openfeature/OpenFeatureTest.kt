package io.github.matthewjones372.lark.app.openfeature

import dev.openfeature.sdk.Client
import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.EventProvider
import dev.openfeature.sdk.Metadata
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.ProviderEventDetails
import dev.openfeature.sdk.ProviderState
import dev.openfeature.sdk.Value
import io.github.matthewjones372.lark.app.Health
import io.github.matthewjones372.lark.app.HealthRegistry
import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeInstanceOf
import io.github.matthewjones372.lark.assert.shouldContain
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.shouldThrow
import io.github.matthewjones372.lark.assert.withClue
import io.github.matthewjones372.lark.openfeature.Flag
import org.junit.jupiter.api.Test

/** A provider that answers every flag with `true`, and can be made to fail its init or go stale. */
private class Switchable(private val failsInit: Boolean = false) : EventProvider() {
    var shutDown = false

    override fun getMetadata() = Metadata { "switchable" }

    override fun initialize(evaluationContext: EvaluationContext?) {
        check(!failsInit) { "the flag service is unreachable" }
    }

    override fun shutdown() {
        shutDown = true
    }

    fun goStale() {
        emitProviderStale(ProviderEventDetails.builder().message("lost its connection").build()).await()
    }

    override fun getBooleanEvaluation(key: String, defaultValue: Boolean, ctx: EvaluationContext) = answer(true)

    override fun getStringEvaluation(key: String, defaultValue: String, ctx: EvaluationContext) = answer(defaultValue)

    override fun getIntegerEvaluation(key: String, defaultValue: Int, ctx: EvaluationContext) = answer(defaultValue)

    override fun getDoubleEvaluation(key: String, defaultValue: Double, ctx: EvaluationContext) = answer(defaultValue)

    override fun getObjectEvaluation(key: String, defaultValue: Value, ctx: EvaluationContext) = answer(defaultValue)

    private fun <A> answer(value: A): ProviderEvaluation<A> = ProviderEvaluation.builder<A>().value(value).build()
}

private class Adoption(val client: Client)

private class Ready(val health: HealthRegistry)

private val registryCheck = Flag.boolean("adoption.registry-check", default = false)

/** Spec 0182: the provider is a node, so nothing reads a flag before it is ready, and its release shuts it down. */
class OpenFeatureTest {

    @Test
    fun `a node taking a Client starts after the provider is ready, and reads it as a Flag does`() {
        val provider = Switchable()
        val graph = openFeature { provider } + single { client: Client -> Adoption(client) }

        val result = graph.use { adoption: Adoption ->
            adoption.client.providerState shouldBe ProviderState.READY
            adoption.client.getBooleanValue("adoption.registry-check", false) to registryCheck.value()
        }
        withClue("start: ${result.leftOrNull()}") { result.isRight() shouldBe true }
        val (injected, declared) = result.getOrNull().shouldNotBeNull()

        withClue("the injected client and a declared Flag read the one provider the graph set") {
            injected shouldBe true
            declared shouldBe true
        }
    }

    @Test
    fun `a provider whose init throws refuses the start`() {
        val graph = openFeature { Switchable(failsInit = true) } + single { client: Client -> Adoption(client) }

        val refused = graph.use { _: Adoption -> }.leftOrNull().shouldNotBeNull()

        refused.shouldBeInstanceOf<StartupError.Refused>().reason shouldContain "the flag service is unreachable"
        withClue("the refused start gave OpenFeature back, so the next graph can install it") {
            openFeature { Switchable() }.use { _: Client -> }.isRight() shouldBe true
        }
    }

    @Test
    fun `a provider that goes stale reports the graph not ready`() {
        val provider = Switchable()
        val graph = openFeature { provider } + single { health: HealthRegistry -> Ready(health) }

        val (before, after) = graph.use { ready: Ready ->
            val before = ready.health.readiness()
            provider.goStale()
            before to ready.health.readiness()
        }.getOrNull().shouldNotBeNull()

        before shouldBe Health.Up
        after shouldBe Health.Down(listOf("openfeature"))
    }

    @Test
    fun `release shuts the SDK down`() {
        val provider = Switchable()

        val started = openFeature { provider }.use { _: Client -> }
        withClue("start: ${started.leftOrNull()}") { started.isRight() shouldBe true }

        provider.shutDown shouldBe true
        OpenFeatureAPI.getInstance().providerMetadata?.name shouldBe "No-op Provider"
    }

    @Test
    fun `a provider that throws before it is set gives OpenFeature back`() {
        shouldThrow<IllegalStateException> { openFeature { error("no provider configured") }.use { _: Client -> } }

        withClue("the next graph can still install OpenFeature") {
            openFeature { Switchable() }.use { _: Client -> }.isRight() shouldBe true
        }
    }

    @Test
    fun `a second graph cannot install OpenFeature while one holds it`() {
        val refused = openFeature { Switchable() }.use { _: Client ->
            openFeature { Switchable() }.use { _: Client -> }.leftOrNull()
        }.getOrNull().shouldNotBeNull()

        refused.shouldBeInstanceOf<StartupError.Refused>().reason shouldContain "already installed"
    }
}
