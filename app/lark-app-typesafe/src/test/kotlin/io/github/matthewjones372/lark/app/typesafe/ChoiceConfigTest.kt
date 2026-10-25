package io.github.matthewjones372.lark.app.typesafe

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.choice
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.assert.shouldBe
import org.junit.jupiter.api.Test

/** A choice reads its setting from the graph's own Config, so overriding one key flips it (spec 0136). */
class ChoiceConfigTest {

    private data class Bus(val kind: String)

    private val app: Module =
        configOf("bus.kind = in-process") +
            config<String>("bus") { string("kind") } +
            choice<String, Bus> { kind -> single<Bus> { Bus(kind) } }

    @Test
    fun `the branch follows the configuration`() {
        testApp(app) { bus: Bus -> bus.kind } shouldBe "in-process"
    }

    @Test
    fun `overridingConfig flips the choice, with nothing reassembled`() {
        testApp(app.overridingConfig("bus.kind = kafka")) { bus: Bus -> bus.kind } shouldBe "kafka"
    }
}
