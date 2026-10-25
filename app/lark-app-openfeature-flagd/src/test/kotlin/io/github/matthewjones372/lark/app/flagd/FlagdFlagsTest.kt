package io.github.matthewjones372.lark.app.flagd

import dev.openfeature.sdk.Client
import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeInstanceOf
import io.github.matthewjones372.lark.assert.shouldContain
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
import io.github.matthewjones372.lark.openfeature.Flag
import io.github.matthewjones372.lark.openfeature.evaluatedFor
import org.junit.jupiter.api.Test

private val limit = Flag.int("adoption.per-customer-limit", default = 1)

/** Spec 0184: flags in flagd's schema, written as a section of the service's own HOCON. */
class FlagdFlagsTest {

    @Test
    fun `a targeted flag in HOCON answers for the key evaluatedFor bound`() {
        val graph = configOf(
            """
            flags {
              "adoption.per-customer-limit" {
                state = ENABLED
                variants { standard = 3, raised = 5 }
                defaultVariant = standard
                targeting { "if" = [{ "==" = [{ "var" = "targetingKey" }, "ada"] }, "raised", "standard"] }
              }
            }
            """.trimIndent(),
        ) + flagdFlags("flags")

        val (ada, bob) = graph.use { _: Client ->
            evaluatedFor("ada") { limit.value() } to evaluatedFor("bob") { limit.value() }
        }.getOrNull().shouldNotBeNull()

        withClue("flagd's in-process resolver evaluated the targeting against the bound key") {
            ada shouldBe 5
            bob shouldBe 3
        }
    }

    @Test
    fun `a section flagd rejects refuses the start, naming the flag and where the section came from`() {
        val graph = configOf(
            """
            flags {
              "adoption.registry-check" { state = SOMETIMES, variants { on = true }, defaultVariant = on }
            }
            """.trimIndent(),
        ) + flagdFlags("flags")

        val refused = graph.use { _: Client -> }.leftOrNull().shouldNotBeNull()

        val reason = refused.shouldBeInstanceOf<StartupError.Refused>().reason
        reason shouldContain "adoption.registry-check"
        reason shouldContain "String"
    }

    @Test
    fun `a graph whose section is missing refuses the start, naming the path`() {
        val refused = (configOf("other = 1") + flagdFlags("flags")).use { _: Client -> }.leftOrNull().shouldNotBeNull()

        refused.shouldBeInstanceOf<StartupError.Refused>().reason shouldContain "flags"
    }
}
