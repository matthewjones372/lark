package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** A secret is only as safe as the least careful thing that holds it, which is usually a log line. */
class SecretTest {

    private val held = Secret("hunter2")

    @Test
    fun `printing one prints the mask`() {
        "$held" shouldBe MASK
    }

    @Test
    fun `and so does printing what holds it`() {
        val said = "connecting with ${listOf(held)}"

        withClue("a collection, an exception and a data class all reach toString") {
            said shouldNotContain "hunter2"
        }
    }

    @Test
    fun `the value comes back only when it is asked for by name`() {
        held.reveal() shouldBe "hunter2"
    }

    @Test
    fun `two of the same secret are the same value`() {
        held shouldBe Secret("hunter2")
    }
}

/** What the report will not print. The guess is the floor; a declared path is the exact answer. */
class SecretsTest {

    @Test
    fun `a name that says what it is, is redacted`() {
        val secrets = Secrets.default

        secrets.redacts("petshop.db.password") shouldBe true
        secrets.redacts("petshop.apiToken") shouldBe true
        secrets.redacts("petshop.aws.secretKey") shouldBe true
    }

    @Test
    fun `a name that does not, is not`() {
        Secrets.default.redacts("petshop.port") shouldBe false
    }

    @Test
    fun `the match is on a segment, not a substring`() {
        withClue("keystore is not a key, and passwordPolicy is about passwords rather than one") {
            Secrets.default.redacts("petshop.keystoreType") shouldBe false
        }
    }

    @Test
    fun `a declared path is redacted where the name would not have been`() {
        val secrets = Secrets.default.and("petshop.db.url")

        withClue("jdbc:postgresql://user:pass@host is the secret a name match misses") {
            secrets.redacts("petshop.db.url") shouldBe true
        }
        secrets.redacts("petshop.port") shouldBe false
    }

    @Test
    fun `the default cannot be shrunk, only added to`() {
        val secrets = Secrets.default.and("petshop.db.url")

        secrets.redacts("petshop.db.password") shouldBe true
    }
}

private data class DbSettings(val url: Secret, val pool: Int)

/** A secret read from a section, which is where a service actually gets one. */
class ReadingASecretTest {

    private val hocon = """db { url = "jdbc:postgresql://user:pass@host/shop", pool = 4 }"""

    private fun read(): DbSettings =
        ConfigFactory.parseString(hocon).reading { section("db") { DbSettings(secret("url"), int("pool")) } }
            .getOrNull()!!

    @Test
    fun `the settings print without the secret in them`() {
        withClue("a data class toString is the leak nobody reviews") {
            "${read()}" shouldNotContain "pass@host"
        }
    }

    @Test
    fun `and the value is there for the thing that needs it`() {
        read().url.reveal() shouldContain "jdbc:postgresql://"
    }

    @Test
    fun `a missing secret is a fault like any other read`() {
        val faults = ConfigFactory.parseString("db { pool = 4 }")
            .reading { section("db") { DbSettings(secret("url"), int("pool")) } }
            .leftOrNull()

        faults.shouldNotBeNull().size shouldBe 1
    }
}
