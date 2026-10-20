package io.github.matthewjones372.lark.app.kotlinx

import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.app.use
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test

private enum class Mode { Fast, Safe }

@Serializable
private data class Moded(val mode: Mode, val fallback: Mode)

class EnumConfigTest {

    @Test
    fun `an enum reads its entry by name`() {
        val file = configOf("petshop { mode = Safe, fallback = Fast }")

        testApp(file + config<Moded>("petshop")) { moded: Moded -> moded } shouldBe Moded(Mode.Safe, Mode.Fast)
    }

    @Test
    fun `an enum that is none of its entries is refused naming the entries it could have been`() {
        val file = configOf("petshop { mode = Reckless, fallback = Slow }")

        val error = (file + config<Moded>("petshop")).use { _: Moded -> }.leftOrNull().shouldNotBeNull()

        val reason = error.shouldBeInstanceOf<StartupError.Refused>().reason
        reason shouldContain "Fast or Safe, not Reckless"
        reason shouldContain "Fast or Safe, not Slow"
    }
}
