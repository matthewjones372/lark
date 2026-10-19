package io.github.matthewjones372.lark.app.kotlinx

import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.app.use
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class Db(val url: String, val replicas: List<String>)

@Serializable
private data class Settings(
    val host: String,
    val port: Int,
    val limit: Long,
    val verbose: Boolean,
    val arrivalsEvery: Duration,
    val db: Db,
)

@Serializable
private data class Optional(
    val port: Int = 8080,
    val proxy: String? = null,
    val fallback: String?,
    val timeout: Duration? = null,
)

class ClassConfigTest {

    @Test
    fun `a class with every supported leaf reads from HOCON`() {
        val file = configOf(
            """
            petshop {
              host = "shop.internal"
              port = 8081
              limit = 5000000000
              verbose = true
              arrivalsEvery = 30s
              db { url = "jdbc:postgresql://db/shop", replicas = [one, two] }
            }
            """,
        )

        val settings = testApp(file + config<Settings>("petshop")) { settings: Settings -> settings }

        settings shouldBe Settings(
            host = "shop.internal",
            port = 8081,
            limit = 5_000_000_000,
            verbose = true,
            arrivalsEvery = 30.seconds,
            db = Db("jdbc:postgresql://db/shop", listOf("one", "two")),
        )
    }

    @Test
    fun `an absent path is the property's default, and an absent nullable is null`() {
        val file = configOf("petshop { timeout = 5s }")

        val settings = testApp(file + config<Optional>("petshop")) { settings: Optional -> settings }

        settings shouldBe Optional(port = 8080, proxy = null, fallback = null, timeout = 5.seconds)
    }

    @Test
    fun `a file with three faults is refused naming all three`() {
        val file = configOf(
            """
            petshop {
              host = "shop.internal"
              port = eighty
              limit = 1
              verbose = true
              arrivalsEvery = soon
              db { replicas = [one] }
            }
            """,
        )

        val error = (file + config<Settings>("petshop")).use { _: Settings -> }.leftOrNull().shouldNotBeNull()

        val reason = error.shouldBeInstanceOf<StartupError.Refused>().reason
        withClue("a port that is not a number, a duration that is not one, and a url missing a section down") {
            reason shouldContain "port"
            reason shouldContain "arrivalsEvery"
            reason shouldContain "url"
        }
    }
}
