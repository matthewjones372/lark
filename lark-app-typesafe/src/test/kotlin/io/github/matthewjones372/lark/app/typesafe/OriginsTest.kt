package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.ConfigFactory
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test

/** Every setting, its value, and the line that said so. */
class OriginsTest {

    private fun parsed(hocon: String) = ConfigFactory.parseString(hocon).resolve()

    @Test
    fun `a setting names its value and where it was written`() {
        val origins = parsed("petshop { port = 9090 }").origins()

        val port = origins.single { it.path == "petshop.port" }
        port.value shouldBe "9090"
        withClue("a string parsed rather than read from a file has no filename, and says so") {
            port.where.shouldNotBeNull()
        }
    }

    @Test
    fun `a file names the file and the line`() {
        val origins = ConfigFactory.parseResources("origins.conf").resolve().origins()

        origins.single { it.path == "petshop.port" }.where shouldContain "origins.conf:2"
    }

    @Test
    fun `paths come out sorted, so two runs read the same`() {
        val origins = parsed("b = 2, a = 1, c = 3").origins()

        origins.map { it.path } shouldContainExactly listOf("a", "b", "c")
    }

    @Test
    fun `a name that says it holds a secret does not print its value`() {
        val origins = parsed("""petshop { db { password = "hunter2", pool = 4 } }""").origins()

        val password = origins.single { it.path == "petshop.db.password" }
        password.value shouldBe MASK
        password.redacted shouldBe true
        withClue("the origin is still worth having: it says which file to go and edit") {
            password.where.shouldNotBeNull()
        }
    }

    @Test
    fun `a declared path does not print its value either`() {
        val hocon = """petshop { db { url = "jdbc:postgresql://user:pass@host/shop" } }"""

        val origins = parsed(hocon).origins(Secrets.default.and("petshop.db.url"))

        origins.single { it.path == "petshop.db.url" }.value shouldBe MASK
    }

    @Test
    fun `the report names every setting and leaks no secret`() {
        val hocon = """petshop { port = 9090, db { password = "hunter2" } }"""

        val said = parsed(hocon).origins().report()

        said shouldContain "lark-app configuration"
        said shouldContain "petshop.port"
        said shouldContain "9090"
        said shouldContain "petshop.db.password"
        said shouldNotContain "hunter2"
    }

    @Test
    fun `a document with nothing in it reports nothing`() {
        parsed("{}").origins().report() shouldBe ""
    }
}
