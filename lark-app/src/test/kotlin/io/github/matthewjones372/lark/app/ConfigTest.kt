package io.github.matthewjones372.lark.app

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

private data class Db(val url: String, val poolSize: Int, val ssl: Boolean)

private data class Http(val port: Int, val idle: kotlin.time.Duration)

private class Pooling(val db: Db)

class ConfigTest {

    private val values = configOf(
        "database.url" to "jdbc:h2:mem:",
        "database.poolSize" to "5",
        "database.ssl" to "true",
        "http.port" to "8080",
        "http.idle" to "30s",
    )

    @Test
    fun `a section is read into the type that names it`() {
        val db = values.read { section("database") { Db(string("url"), int("poolSize"), boolean("ssl")) } }

        db.getOrNull() shouldBe Db("jdbc:h2:mem:", 5, true)
    }

    @Test
    fun `every fault comes back, not the first`() {
        val faults = configOf("http.port" to "eighty").read {
            section("http") { Http(int("port"), duration("idle")) }
        }.leftOrNull().shouldNotBeNull()

        withClue("one message listing what is wrong beats one deploy per fault") {
            faults.size shouldBe 2
            faults.map { it.describe() }.joinToString() shouldContain "http.port is not an Int"
            faults.map { it.describe() }.joinToString() shouldContain "http.idle is not set"
        }
    }

    @Test
    fun `an optional path falls back without a fault`() {
        val http = configOf("http.port" to "9090").read {
            section("http") { Http(int("port"), optional("idle", 30.seconds) { duration(it) }) }
        }

        http.getOrNull() shouldBe Http(9090, 30.seconds)
    }

    @Test
    fun `a source falls back to the next one`() {
        val chained = configOf("http.port" to "1").orElse(configOf("http.port" to "2", "http.idle" to "5s"))

        chained.at("http.port") shouldBe "1"
        chained.at("http.idle") shouldBe "5s"
    }

    @Test
    fun `a module reads its own section and nothing else`() {
        val app = single<Config> { values } +
            configured("database") { Db(string("url"), int("poolSize"), boolean("ssl")) } +
            single { db: Db -> Pooling(db) }

        testApp(app) { pooling: Pooling -> pooling.db.poolSize } shouldBe 5
    }

    @Test
    fun `a section that cannot be read refuses the start, naming every fault`() {
        val app = single<Config> { configOf("database.poolSize" to "lots") } +
            configured("database") { Db(string("url"), int("poolSize"), boolean("ssl")) } +
            single { db: Db -> Pooling(db) }

        val error = app.use { _: Pooling -> }.leftOrNull().shouldNotBeNull()

        val refused = error.shouldBeInstanceOf<StartupError.Refused>()
        refused.reason shouldContain "database.url is not set"
        refused.reason shouldContain "database.poolSize is not an Int: lots"
        refused.reason shouldContain "database.ssl is not set"
    }
}

private interface Carts

private class RedisCarts : Carts

private class PostgresCarts : Carts

class ChoosingTest {

    private val redis: Module = single<Carts> { RedisCarts() }

    private val postgres: Module = single<Carts> { PostgresCarts() }

    private fun carts(config: Config): Module =
        config.choose("cartStore", default = "redis", "redis" to redis, "postgres" to postgres)

    @Test
    fun `the value names the module`() {
        val chosen = carts(configOf("cartStore" to "postgres")) + single<Config> { configOf() }

        testApp(chosen) { carts: Carts -> carts }.shouldBeInstanceOf<PostgresCarts>()
    }

    @Test
    fun `an unset value takes the default`() {
        val chosen = carts(configOf()) + single<Config> { configOf() }

        testApp(chosen) { carts: Carts -> carts }.shouldBeInstanceOf<RedisCarts>()
    }

    @Test
    fun `the branch not taken contributes nothing`() {
        val built = java.util.concurrent.atomic.AtomicInteger()
        val counted: Module = single<Carts> { built.incrementAndGet(); PostgresCarts() }
        val chosen = configOf("cartStore" to "redis")
            .choose("cartStore", "redis", "redis" to redis, "postgres" to counted)

        testApp(chosen + single<Config> { configOf() }) { _: Carts -> }

        withClue("a module that was not chosen is not in the graph at all") { built.get() shouldBe 0 }
    }

    @Test
    fun `a value naming no branch says what it could have been`() {
        val failure = io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            carts(configOf("cartStore" to "cassandra"))
        }

        failure.message.orEmpty() shouldContain "cartStore is cassandra"
        failure.message.orEmpty() shouldContain "redis, postgres"
    }
}
