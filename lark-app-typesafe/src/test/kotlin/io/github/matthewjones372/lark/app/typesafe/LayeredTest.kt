package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** What a merge threw away, kept, because that is the half that answers "why is it this?". */
class LayeredTest {

    private val reference = ConfigFactory.parseString("petshop { port = 8080, db { pool = 4 } }")

    private val application = ConfigFactory.parseString("petshop { port = 9090 }")

    private fun layered() = layeredConfigOf(
        "application.conf" to application,
        "reference.conf" to reference,
    )

    @Test
    fun `the merge is what the layers resolve to`() {
        layered().config.getInt("petshop.port") shouldBe 9090
    }

    @Test
    fun `a setting that was overridden names what it replaced`() {
        val port = layered().origins().single { it.path == "petshop.port" }

        port.value shouldBe "9090"
        val was = port.overrides.shouldNotBeNull()
        was.value shouldBe "8080"
    }

    @Test
    fun `a setting only one layer supplied says nothing extra`() {
        layered().origins().single { it.path == "petshop.db.pool" }.overrides.shouldBeNull()
    }

    @Test
    fun `the report says what a setting overrode`() {
        val said = layered().origins().report()

        said shouldContain "petshop.port"
        said shouldContain "overrides 8080"
    }

    @Test
    fun `an overridden secret does not print either value`() {
        val layers = layeredConfigOf(
            "application.conf" to ConfigFactory.parseString("""petshop.db.password = "new" """),
            "reference.conf" to ConfigFactory.parseString("""petshop.db.password = "old" """),
        )

        val said = layers.origins().report()

        withClue("a report that masks the winner and prints the loser has leaked the secret") {
            said shouldNotContain "new"
            said shouldNotContain "old"
        }
        said shouldContain "petshop.db.password"
    }

    @Test
    fun `a substitution across layers resolves against the whole document`() {
        val layers = layeredConfigOf(
            "application.conf" to ConfigFactory.parseString("""petshop.db.url = "jdbc:"${'$'}{petshop.db.name}"""),
            "reference.conf" to ConfigFactory.parseString("""petshop.db.name = "shop" """),
        )

        layers.config.getString("petshop.db.url") shouldBe "jdbc:shop"
    }
}

/**
 * `ConfigFactory.defaultReference()` carries system properties, so a report built on it says
 * reference.conf holds a value that came from `-D`. This is the test that stops that coming back.
 */
class CleanLayersTest {

    @Test
    fun `the reference layer is the file, not the file with overrides on top`() {
        val property = "lark.layered.test.port"
        System.setProperty(property, "6000")
        try {
            ConfigFactory.invalidateCaches()

            withClue("defaultReference merges system properties in, which is why it is not used") {
                ConfigFactory.defaultReference().getInt(property) shouldBe 6000
            }
            val layers = layeredConfig().layers

            withClue("a layer named for a file must hold only what that file said") {
                layers.none { it.name != "system properties" && it.config.hasPath(property) } shouldBe true
            }
            layers.first().name shouldBe "system properties"
        } finally {
            System.clearProperty(property)
            ConfigFactory.invalidateCaches()
        }
    }
}
