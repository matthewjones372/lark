package io.github.matthewjones372.lark.app.flagd

import dev.openfeature.sdk.Client
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.app.use
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.openfeature.Flag
import io.github.matthewjones372.lark.openfeature.evaluatedFor
import io.github.matthewjones372.lark.resource

private val limit = Flag.int("adoption.per-customer-limit", default = 1)

/** Spec 0184: flags in flagd's schema, written as a section of the service's own HOCON. */
// Every test starts flagd as the provider of OpenFeatureAPI, a process-wide singleton that one graph holds at a
// time, so two at once refuse each other's start: run concurrently, two of the three failed.
object FlagdFlagsSuite : Suite<Unit>(concurrent = false) {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a targeted flag in HOCON answers for the key evaluatedFor bound") { _ ->
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

            graph.use { _: Client ->
                evaluatedFor("ada") { limit.value() } to evaluatedFor("bob") { limit.value() }
            }.getOrNull().shouldNotBeNull { (ada, bob) ->
                withClue("flagd's in-process resolver evaluated the targeting against the bound key") {
                    (ada shouldBe 5) and (bob shouldBe 3)
                }
            }
        }

        test("a section flagd rejects refuses the start, naming the flag and where the section came from") { _ ->
            val graph = configOf(
                """
                flags {
                  "adoption.registry-check" { state = SOMETIMES, variants { on = true }, defaultVariant = on }
                }
                """.trimIndent(),
            ) + flagdFlags("flags")

            graph.use { _: Client -> }.leftOrNull().shouldNotBeNull { refused ->
                refused.shouldBeInstanceOf<StartupError.Refused> {
                    (it.reason shouldContain "adoption.registry-check") and (it.reason shouldContain "String")
                }
            }
        }

        test("a graph whose section is missing refuses the start, naming the path") { _ ->
            (configOf("other = 1") + flagdFlags("flags")).use { _: Client -> }.leftOrNull().shouldNotBeNull { refused ->
                refused.shouldBeInstanceOf<StartupError.Refused> { it.reason shouldContain "flags" }
            }
        }
    }
}
