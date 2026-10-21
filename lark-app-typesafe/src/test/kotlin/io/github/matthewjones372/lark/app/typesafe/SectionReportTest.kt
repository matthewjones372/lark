package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.use
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

private class WholeDb(val settings: Config)

private data class Shop(val host: String, val port: Int, val url: Secret, val password: String)

/** Each section logs what it read, as it reads it, and nothing it did not (spec 0155). */
class SectionReportTest {

    private val logged = mutableListOf<String>()

    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            synchronized(logged) { logged += record.message }
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    private val logger = Logger.getLogger("lark-app")

    @BeforeEach
    fun listening() = logger.addHandler(handler)

    @AfterEach
    fun deaf() = logger.removeHandler(handler)

    private val hocon =
        """
        petshop {
          host = "0.0.0.0", port = 9090, unread = "left alone"
          url = "jdbc:postgresql://user:hunter2@db/shop", password = "hunter3"
        }
        """.trimIndent()

    private val shop = config<Shop>("petshop") {
        Shop(string("host"), int("port"), secret("url"), string("password"))
    }

    private fun block(): String = synchronized(logged) { logged.single { "configuration: petshop" in it } }

    @Test
    fun `a section logs the paths it read, masked by how they were read and by name`() {
        testApp(configOf(hocon) + shop) { _: Shop -> Unit }

        val block = block()
        block shouldContain "petshop.host"
        block shouldContain "9090"
        withClue("only what the section read is printed") { block shouldNotContain "unread" }
        withClue("the url's name says nothing; secret() is what masks it") { block shouldNotContain "hunter2" }
        withClue("Secrets.default still masks a password read as a plain string") { block shouldNotContain "hunter3" }
        block shouldContain MASK
    }

    @Test
    fun `a section over layers says what each setting overrode`() {
        val base = layeredConfigOf("base" to ConfigFactory.parseString(hocon))
        val app = configOf(hocon).overridingConfig("petshop.port = 8081", fallback = base) + shop

        testApp(app) { _: Shop -> Unit }

        block() shouldContain "overrides 9090"
    }

    @Test
    fun `a layered merge is a Config Typesafe can merge under another, and still reports its layers`() {
        val base = layeredConfigOf("base" to ConfigFactory.parseString(hocon))

        withClue("withFallback casts its argument to Typesafe's own Config, so the merge has to be one") {
            val above = ConfigFactory.parseString("petshop.port = 8081")
            above.withFallback(base.config).getInt("petshop.port") shouldBe 8081
        }
        val app = configOf(hocon).overridingConfig("petshop.port = 8082", fallback = base.config) + shop
        testApp(app) { _: Shop -> Unit }

        block() shouldContain "8082"
    }

    @Test
    fun `a setting read by its quoted path is masked as its plain one would be`() {
        val quoted = config<String>("petshop") { string("\"password\"") }

        testApp(configOf(hocon) + quoted) { _: String -> Unit }

        block() shouldNotContain "hunter3"
    }

    @Test
    fun `an object read whole is reported as its settings, each masked as it would be alone`() {
        val whole = config<WholeDb>("petshop") { WholeDb(of("db", ConfigFactory.empty()) { getConfig("db") }) }
        val nested = """petshop { db { user = "shop", password = "hunter4" } }"""

        testApp(configOf(nested) + whole) { _: WholeDb -> Unit }

        val block = block()
        block shouldContain "petshop.db.user"
        withClue("a password under an object read whole is still a password") { block shouldNotContain "hunter4" }
    }

    @Test
    fun `a hand-written Config, with no layers, is still reported`() {
        val app = single<Config> { ConfigFactory.parseString(hocon).resolve() } + shop

        testApp(app) { _: Shop -> Unit }

        block() shouldContain "petshop.port"
    }

    @Test
    fun `a section that refuses the start logs what it did read first`() {
        val app = configOf("""petshop { host = "0.0.0.0" }""") + shop

        val refused = app.use { _: Shop -> Unit }.leftOrNull()

        refused.shouldBeInstanceOf<StartupError.Refused>()
        val block = block()
        block shouldContain "petshop.host"
        withClue("a path asked for and absent says so, rather than vanishing from the report") {
            block shouldContain "petshop.port"
        }
    }

    @Test
    fun `a section chosen on logs its block when the graph starts`() {
        val root = ConfigFactory.parseString(hocon)
        val app = configOf(root) + root.choosing("petshop", { string("host") }) { single<Int> { 1 } }

        testApp(app) { host: String -> host } shouldBe "0.0.0.0"

        synchronized(logged) { logged.filter { "configuration: petshop" in it } } shouldHaveSize 1
    }
}
