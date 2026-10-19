package io.github.matthewjones372.lark.app.kotlinx

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.typesafe.MASK
import io.github.matthewjones372.lark.app.typesafe.Secrets
import io.github.matthewjones372.lark.app.typesafe.configOf
import io.github.matthewjones372.lark.app.typesafe.origins
import io.github.matthewjones372.lark.app.use
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Serializable
private data class Store(val dbUrl: Secret, val replica: Replica)

@Serializable
private data class Replica(val readUrl: Secret)

@Serializable
private data class Kebabed(
    val arrivalsEvery: Duration,
    @SerialName("outbox_every") val outboxEvery: Duration,
    val nestedSection: Inner,
)

@Serializable
private data class Inner(val maxRetries: Int)

private enum class Mode { Fast, Safe }

@Serializable
private data class Moded(val mode: Mode, val fallback: Mode)

@Serializable
private data class Repo(val useCache: Boolean)

private interface Store2

private class Cached : Store2

private class Plain : Store2

class NamingTest {

    @Test
    fun `under kebab naming arrivalsEvery reads arrivals-every, and a SerialName is the key as written`() {
        val file = configOf(
            """
            petshop {
              arrivals-every = 30s
              outbox_every = 5s
              nested-section { max-retries = 3 }
            }
            """,
        )

        val settings = testApp(file + config<Kebabed>("petshop", Naming.Kebab)) { settings: Kebabed -> settings }

        settings shouldBe Kebabed(30.seconds, 5.seconds, Inner(3))
    }

    @Test
    fun `a Secret property masks itself in toString and in the report`() {
        val file = configOf(
            """
            petshop {
              dbUrl = "jdbc:postgresql://user:hunter2@db/shop"
              replica { readUrl = "jdbc:postgresql://user:hunter3@replica/shop" }
            }
            """,
        )

        val store = testApp(file + config<Store>("petshop")) { store: Store -> store }

        store.dbUrl.reveal() shouldBe "jdbc:postgresql://user:hunter2@db/shop"
        store.toString() shouldNotContain "hunter"
        store.toString() shouldContain MASK
        val report = ConfigFactory.parseString(
            """
            petshop {
              dbUrl = "jdbc:postgresql://user:hunter2@db/shop"
              replica { readUrl = "jdbc:postgresql://user:hunter3@replica/shop" }
            }
            """,
        ).origins(Secrets.default.andSecretsOf<Store>("petshop")).report()
        withClue("neither name says secret, so only the class can tell the report") {
            report shouldNotContain "hunter"
            report shouldContain "petshop.replica.readUrl"
        }
    }

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

    @Test
    fun `choosing with no block reads the choice from the class`() {
        val hocon = ConfigFactory.parseString("repo { useCache = true }").resolve()
        val cached: Module = single<Store2> { Cached() }
        val plain: Module = single<Store2> { Plain() }

        val app = configOf(hocon) + hocon.choosing<Repo>("repo") { repo -> if (repo.useCache) cached else plain }

        testApp(app) { store: Store2 -> store }.shouldBeInstanceOf<Cached>()
        testApp(app) { repo: Repo -> repo } shouldBe Repo(useCache = true)
    }
}
