package io.github.matthewjones372.lark.actor.benchmarks

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.ShardedJournal
import io.github.matthewjones372.lark.actor.journal
import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.app.liquibase.migrate
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val ACCOUNTS = 256
private const val SPREAD_PAYMENTS = 10_000
private const val POOL = 32

private data class Spread(val cents: Int, val done: CountDownLatch)

private val spreadCents = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = "$event".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

/**
 * [SPREAD_PAYMENTS] payments over [ACCOUNTS] persistent actors, one event and one append each, on a journal across
 * [databases] Postgres servers in the benchmark's JVM (spec 0088). The writes are the many-ids kind sharding spreads;
 * the one-account kind is [HotPersistentBenchmark]'s.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
open class SpreadPersistentBenchmark {

    @Param("1", "2")
    var databases: Int = 1

    private lateinit var servers: List<EmbeddedPostgres>
    private lateinit var pools: List<HikariDataSource>
    private lateinit var accounts: List<ActorRef<Spread>>

    @Setup(Level.Trial)
    fun start(lark: Lark) {
        // Durable commits, as a service runs: the embedded default of neither makes one server CPU-bound, not
        // commit-bound, and a second server on the same cores then has nothing of its own to add.
        servers = (1..databases).map {
            EmbeddedPostgres.builder()
                .setServerConfig("fsync", "on")
                .setServerConfig("synchronous_commit", "on")
                .start()
        }
        pools = servers.map { server ->
            migrate(server.postgresDatabase, "lark/journal/jdbc/postgres.sql")
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = server.getJdbcUrl("postgres", "postgres")
                    maximumPoolSize = POOL
                },
            )
        }
        lark.flock.journal(ShardedJournal(pools.mapIndexed { i, pool -> "db-$i" to JdbcJournal(pool) }))
        val run = UUID.randomUUID()
        accounts = (1..ACCOUNTS).map { n ->
            lark.actor(
                "account-$databases-$n",
                persistent<Spread, Int, Int>(
                    id = PersistenceId("account", "$run-$n"),
                    empty = 0,
                    codec = spreadCents,
                    command = { _, _, pay -> persist(pay.cents).then { pay.done.countDown() } },
                    event = { sum, paid -> sum + paid },
                ),
            )
        }
    }

    @TearDown(Level.Trial)
    fun stop() {
        pools.forEach(HikariDataSource::close)
        servers.forEach(EmbeddedPostgres::close)
    }

    @Benchmark
    fun payments() {
        val done = CountDownLatch(SPREAD_PAYMENTS)
        repeat(SPREAD_PAYMENTS) { accounts[it % ACCOUNTS].tell(Spread(1, done)) }
        done.await()
    }
}
