package io.github.matthewjones372.lark.actor.journal.jdbc

import arrow.core.Either
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.awaitIdle
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.journal
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

private val cents = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = "$event".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

/** Spec 0086 on Postgres: commands waiting at once become one statement's worth of rows each batch. */
class BatchedAppendTest {

    private class Counted(private val kept: Journal) : Journal by kept {
        val appends = AtomicInteger()

        override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> =
            kept.append(id, expected, events).also { appends.incrementAndGet() }
    }

    @Test
    fun `a thousand commands waiting at once reach Postgres in a handful of appends, every event in order`() {
        val id = PersistenceId("merchant", "m-1")
        val journal = Counted(JdbcJournal(Postgres.fresh()))
        val opened = CountDownLatch(1)
        val merchant = persistent<Int, Int, Int>(
            id = id,
            empty = 0,
            codec = cents,
            command = { _, _, paid -> persist(paid) },
            event = { sum, paid -> sum + paid },
            batch = 64,
        ).onStart { opened.await() }

        flock<Nothing, Unit> {
            journal(journal)
            val ref = spawn("merchant", merchant)
            (1..1_000).forEach(ref::tell)
            opened.countDown()
            awaitIdle()
        }

        journal.appends.get() shouldBeLessThanOrEqual 20
        journal.events(id, cents) shouldContainExactly (1..1_000).toList()
    }
}
