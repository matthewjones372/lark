package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeNull
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.junit.shouldNotContain
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource

/** What a merge threw away, kept, because that is the half that answers "why is it this?". */
object LayeredSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private val reference = ConfigFactory.parseString("petshop { port = 8080, db { pool = 4 } }")

    private val application = ConfigFactory.parseString("petshop { port = 9090 }")

    private fun layered() = layeredConfigOf(
        "application.conf" to application,
        "reference.conf" to reference,
    )

    /** The HOCON override pair, `a = "x"` then `a = ${'$'}{?ENV}`, which Typesafe holds as a delayed merge. */
    private fun overridden(env: String) = layeredConfigOf(
        "application.conf" to ConfigFactory.parseString(
            """
            petshop.bus = "in-process"
            petshop.bus = ${'$'}{?$env}
            """.trimIndent(),
        ),
        "reference.conf" to ConfigFactory.parseString("""petshop.bus = "kafka" """),
    )

    init {
        test("the merge is what the layers resolve to") { _ ->
            layered().config.getInt("petshop.port") shouldBe 9090
        }

        test("a setting that was overridden names what it replaced") { _ ->
            val port = layered().origins().single { it.path == "petshop.port" }

            (port.value shouldBe "9090") and port.overrides.shouldNotBeNull { was -> was.value shouldBe "8080" }
        }

        test("a setting only one layer supplied says nothing extra") { _ ->
            layered().origins().single { it.path == "petshop.db.pool" }.overrides.shouldBeNull()
        }

        test("the report says what a setting overrode") { _ ->
            val said = layered().origins().report()

            (said shouldContain "petshop.port") and (said shouldContain "overrides 8080")
        }

        test("an overridden secret does not print either value") { _ ->
            val layers = layeredConfigOf(
                "application.conf" to ConfigFactory.parseString("""petshop.db.password = "new" """),
                "reference.conf" to ConfigFactory.parseString("""petshop.db.password = "old" """),
            )

            val said = layers.origins().report()

            withClue("a report that masks the winner and prints the loser has leaked the secret") {
                (said shouldNotContain "new") and (said shouldNotContain "old")
            } and (said shouldContain "petshop.db.password")
        }

        test("a substitution across layers resolves against the whole document") { _ ->
            val layers = layeredConfigOf(
                "application.conf" to ConfigFactory.parseString("""petshop.db.url = "jdbc:"${'$'}{petshop.db.name}"""),
                "reference.conf" to ConfigFactory.parseString("""petshop.db.name = "shop" """),
            )

            (layers.config.getString("petshop.db.url") shouldBe "jdbc:shop") and
                (layers.origins().single { it.path == "petshop.db.url" }.value shouldBe "jdbc:shop")
        }

        test("a default an unset environment variable would override keeps the default and names what it beat") { _ ->
            val bus = overridden("LARK_LAYERED_TEST_UNSET").origins().single { it.path == "petshop.bus" }

            (bus.value shouldBe "in-process") and bus.overrides.shouldNotBeNull { it.value shouldBe "kafka" }
        }

        test("a default a set environment variable overrides takes the variable and names what it beat") { _ ->
            System.getenv("PATH").shouldNotBeNull { path ->
                val bus = overridden("PATH").origins().single { it.path == "petshop.bus" }

                (bus.value shouldBe path) and bus.overrides.shouldNotBeNull { it.value shouldBe "kafka" }
            }
        }

        test("an override pair in a lower layer is what the winner beat") { _ ->
            val layers = layeredConfigOf(
                "system properties" to ConfigFactory.parseString("""petshop.bus = "local" """),
                "application.conf" to overridden("LARK_LAYERED_TEST_UNSET").layers.first().config,
            )

            val bus = layers.origins().single { it.path == "petshop.bus" }

            (bus.value shouldBe "local") and bus.overrides.shouldNotBeNull { it.value shouldBe "in-process" }
        }

        test("an override pair whose substitution another layer supplies takes that layer's value") { _ ->
            val layers = layeredConfigOf(
                "application.conf" to ConfigFactory.parseString(
                    """
                    petshop.bus = "in-process"
                    petshop.bus = ${'$'}{petshop.chosen}
                    """.trimIndent(),
                ),
                "reference.conf" to ConfigFactory.parseString("""petshop { bus = "kafka", chosen = "nats" }"""),
            )

            val bus = layers.origins().single { it.path == "petshop.bus" }

            (bus.value shouldBe "nats") and bus.overrides.shouldNotBeNull { it.value shouldBe "kafka" }
        }

        test("an optional substitution left unset supplies nothing to beat") { _ ->
            val layers = layeredConfigOf(
                "application.conf" to ConfigFactory.parseString("petshop.bus = ${'$'}{?LARK_LAYERED_TEST_UNSET}"),
                "reference.conf" to ConfigFactory.parseString("""petshop.bus = "kafka" """),
            )

            val bus = layers.origins().single { it.path == "petshop.bus" }

            (bus.value shouldBe "kafka") and bus.overrides.shouldBeNull()
        }
    }
}

/**
 * `ConfigFactory.defaultReference()` carries system properties, so a report built on it says
 * reference.conf holds a value that came from `-D`. This is the test that stops that coming back.
 */
object CleanLayersSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        // This suite's one test sets a system property and clears ConfigFactory's caches, both process-wide.
        // Suites run one after another, so no other suite's test sees them while it runs.
        test("the reference layer is the file, not the file with overrides on top") { _ ->
            val property = "lark.layered.test.port"
            System.setProperty(property, "6000")
            try {
                ConfigFactory.invalidateCaches()

                val default = withClue("defaultReference merges system properties in, which is why it is not used") {
                    ConfigFactory.defaultReference().getInt(property) shouldBe 6000
                }
                val layers = layeredConfig().layers

                default and
                    withClue("a layer named for a file must hold only what that file said") {
                        layers.none { it.name != "system properties" && it.config.hasPath(property) } shouldBe true
                    } and
                    (layers.first().name shouldBe "system properties")
            } finally {
                System.clearProperty(property)
                ConfigFactory.invalidateCaches()
            }
        }
    }
}
