package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.StartupError
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.use
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import com.typesafe.config.Config as Hocon

private data class Db(val url: String, val poolSize: Int, val idle: Duration, val replicas: List<String>)

private class Pooling(val db: Db)

private class Checked(val url: String) {
    init {
        require(url.isNotEmpty()) { "a url is required" }
    }
}

class SectionsTest {

    private val file: Hocon = ConfigFactory.parseResources("application.conf").resolve()

    private fun db(): Reading.() -> Db = {
        Db(string("url"), int("poolSize"), duration("idle"), strings("replicas"))
    }

    @Test
    fun `everything HOCON can say still works through the reading`() {
        val db = file.reading { section("database", db()) }.getOrNull().shouldNotBeNull()

        withClue("substitution, a list and a duration, none of them re-encoded as a string") {
            db.url shouldBe "jdbc:postgresql://db.internal/shopping"
            db.idle shouldBe 30.seconds
            db.replicas shouldContainExactly listOf("one", "two")
        }
    }

    @Test
    fun `every fault comes back, in Typesafe Config's own words`() {
        val faults = file.reading { section("broken", db()) }.leftOrNull().shouldNotBeNull()

        withClue("one message listing what is wrong beats one deploy per fault") {
            faults.size shouldBe 4
        }
        val said = faults.joinToString { it.why }
        said shouldContain "url"
        withClue("the exception's own message names the origin, which this could not word better") {
            said shouldContain "application.conf"
        }
    }

    @Test
    fun `a constructor that validates a discarded value still answers with the faults`() {
        val faults = file.reading { section("broken") { Checked(string("url")) } }
            .leftOrNull().shouldNotBeNull()

        withClue("the missing url is what to fix; the require it tripped on the way past is not") {
            faults.joinToString { it.why } shouldContain "url"
        }
    }

    @Test
    fun `a module reads its own section and nothing else names its paths`() {
        val app = single<Hocon> { file } + config("database", db()) + single { db: Db -> Pooling(db) }

        testApp(app) { pooling: Pooling -> pooling.db.poolSize } shouldBe 5
    }

    @Test
    fun `a section that cannot be read refuses the start`() {
        val app = single<Hocon> { file } + config("broken", db()) + single { db: Db -> Pooling(db) }

        val error = app.use { _: Pooling -> }.leftOrNull().shouldNotBeNull()

        error.shouldBeInstanceOf<StartupError.Refused>().reason shouldContain "url"
    }

    @Test
    fun `the when a service writes over its own settings is a Module, and needs nothing from here`() {
        val carts: io.github.matthewjones372.lark.app.Module =
            when (file.getString("database.url").substringAfter("://").substringBefore("/")) {
                "db.internal" -> single<Pooling> { Pooling(Db("", 0, Duration.ZERO, emptyList())) }
                else -> single<Pooling> { Pooling(Db("other", 0, Duration.ZERO, emptyList())) }
            }

        testApp(carts) { pooling: Pooling -> pooling.db.url } shouldBe ""
    }
}
