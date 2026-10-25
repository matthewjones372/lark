package io.github.matthewjones372.lark.app.kotlinx

import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import kotlinx.serialization.Serializable

private enum class Mode { Fast, Safe }

@Serializable
private data class Moded(val mode: Mode, val fallback: Mode)

object EnumConfigSuite : Suite() {
    init {
        test("an enum reads its entry by name") {
            val file = configOf("petshop { mode = Safe, fallback = Fast }")

            testApp(file + config<Moded>("petshop")) { moded: Moded -> moded } shouldBe Moded(Mode.Safe, Mode.Fast)
        }

        test("an enum that is none of its entries is refused naming the entries it could have been") {
            val file = configOf("petshop { mode = Reckless, fallback = Slow }")

            (file + config<Moded>("petshop")).use { _: Moded -> }.leftOrNull().shouldNotBeNull { error ->
                error.shouldBeInstanceOf<StartupError.Refused> { refused ->
                    (refused.reason shouldContain "Fast or Safe, not Reckless") and
                        (refused.reason shouldContain "Fast or Safe, not Slow")
                }
            }
        }
    }
}
