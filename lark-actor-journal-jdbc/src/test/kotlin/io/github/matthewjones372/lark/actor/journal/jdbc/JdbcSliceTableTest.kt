package io.github.matthewjones372.lark.actor.journal.jdbc

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds

/** Spec 0105: the slice table is read by one caller at a time, however many appends want it. */
class JdbcSliceTableTest {

    private val opened = AtomicInteger()

    // Each connection taken slowly, as from a busy pool, and counted.
    private val source: DataSource = Postgres.fresh().let { postgres ->
        object : DataSource by postgres {
            override fun getConnection(): Connection {
                opened.incrementAndGet()
                Thread.sleep(20)
                return postgres.connection
            }
        }
    }

    @Test
    fun `a thousand appends asking a stale table at once read it once, and go on with the map they have`() {
        val table = JdbcSliceTable(source, listOf("db-0", "db-1"), refreshEvery = 50.milliseconds)
        val first = table.current()
        Thread.sleep(60)
        opened.set(0)

        val go = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            repeat(1_000) { threads.submit { go.await().also { table.current() shouldBe first } } }
            go.countDown()
        }

        opened.get() shouldBeLessThanOrEqual 2
    }

    @Test
    fun `refreshes asked while one is reading share its read`() {
        val table = JdbcSliceTable(source, listOf("db-0", "db-1"))
        table.current()
        opened.set(0)

        val go = CountDownLatch(1)
        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            repeat(200) { threads.submit { go.await().also { table.refresh() } } }
            go.countDown()
        }

        opened.get() shouldBeLessThanOrEqual 10
    }
}
