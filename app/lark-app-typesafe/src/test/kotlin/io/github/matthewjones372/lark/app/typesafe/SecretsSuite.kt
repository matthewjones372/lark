package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.junit.shouldNotContain
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource

/** A secret is only as safe as the least careful thing that holds it, which is usually a log line. */
object SecretSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private val held = Secret("hunter2")

    init {
        test("printing one prints the mask") { _ ->
            "$held" shouldBe MASK
        }

        test("and so does printing what holds it") { _ ->
            val said = "connecting with ${listOf(held)}"

            withClue("a collection, an exception and a data class all reach toString") {
                said shouldNotContain "hunter2"
            }
        }

        test("the value comes back only when it is asked for by name") { _ ->
            held.reveal() shouldBe "hunter2"
        }

        test("two of the same secret are the same value") { _ ->
            held shouldBe Secret("hunter2")
        }
    }
}

/** What the report will not print. The guess is the floor; a declared path is the exact answer. */
object SecretsSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a name that says what it is, is redacted") { _ ->
            val secrets = Secrets.default

            (secrets.redacts("petshop.db.password") shouldBe true) and
                (secrets.redacts("petshop.apiToken") shouldBe true) and
                (secrets.redacts("petshop.aws.secretKey") shouldBe true)
        }

        test("a name that does not, is not") { _ ->
            Secrets.default.redacts("petshop.port") shouldBe false
        }

        test("the match is on a segment, not a substring") { _ ->
            withClue("keystore is not a key, and passwordPolicy is about passwords rather than one") {
                Secrets.default.redacts("petshop.keystoreType") shouldBe false
            }
        }

        test("a declared path is redacted where the name would not have been") { _ ->
            val secrets = Secrets.default.and("petshop.db.url")

            withClue("jdbc:postgresql://user:pass@host is the secret a name match misses") {
                secrets.redacts("petshop.db.url") shouldBe true
            } and (secrets.redacts("petshop.port") shouldBe false)
        }

        test("the default cannot be shrunk, only added to") { _ ->
            val secrets = Secrets.default.and("petshop.db.url")

            secrets.redacts("petshop.db.password") shouldBe true
        }
    }
}

private data class DbSettings(val url: Secret, val pool: Int)

/** A secret read from a section, which is where a service actually gets one. */
object ReadingASecretSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private const val HOCON = """db { url = "jdbc:postgresql://user:pass@host/shop", pool = 4 }"""

    private fun read(): DbSettings =
        ConfigFactory.parseString(HOCON).reading { section("db") { DbSettings(secret("url"), int("pool")) } }
            .getOrNull()!!

    init {
        test("the settings print without the secret in them") { _ ->
            withClue("a data class toString is the leak nobody reviews") {
                "${read()}" shouldNotContain "pass@host"
            }
        }

        test("and the value is there for the thing that needs it") { _ ->
            read().url.reveal() shouldContain "jdbc:postgresql://"
        }

        test("a missing secret is a fault like any other read") { _ ->
            val faults = ConfigFactory.parseString("db { pool = 4 }")
                .reading { section("db") { DbSettings(secret("url"), int("pool")) } }
                .leftOrNull()

            faults.shouldNotBeNull { it.size shouldBe 1 }
        }
    }
}
