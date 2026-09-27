package io.github.matthewjones372.lark.actor.journal.jdbc

import arrow.core.Either
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Prune
import io.github.matthewjones372.lark.actor.ShardedJournal
import io.github.matthewjones372.lark.actor.ShardedSnapshots
import io.github.matthewjones372.lark.actor.StateCodec
import io.github.matthewjones372.lark.actor.awaitIdle
import io.github.matthewjones372.lark.actor.events
import io.github.matthewjones372.lark.actor.every
import io.github.matthewjones372.lark.actor.journal
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.actor.snapshots
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private val shardedCents = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = "$event".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private val shardedSum = object : StateCodec<Int> {
    override fun encode(state: Int): ByteArray = "$state".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

/** Spec 0088 on Postgres: two databases, each with its own `ordering`, behind one journal. */
class ShardedPostgresTest {

    private val a = Postgres.fresh()
    private val b = Postgres.fresh()
    private val journal = ShardedJournal(listOf("db-a" to JdbcJournal(a), "db-b" to JdbcJournal(b)))

    private fun till(id: PersistenceId, prune: Prune = Prune.never, batch: Int = 64) = persistent<Int, Int, Int>(
        id = id,
        empty = 0,
        codec = shardedCents,
        command = { _, _, paid -> persist(paid) },
        event = { sum, paid -> sum + paid },
        snapshots = every(10, shardedSum, prune),
        batch = batch,
    )

    @Test
    fun `entities spread over two databases, and each database's feed holds its own ids' events once`() {
        val ids = (1..200).map { PersistenceId("till", "t-$it") }
        flock<Nothing, Unit> {
            journal(journal)
            val tills = ids.map { spawn(it.id, till(it)) }
            (1..3).forEach { paid -> tills.forEach { it.tell(paid) } }
            awaitIdle()
        }

        ids.forEach { id -> journal.events(id, shardedCents) shouldContainExactly listOf(1, 2, 3) }
        val fed = journal.feeds.associate { (database, feed) -> database to feed.after("till", 0, 10_000) }
        fed.values.flatten().map { it.id to it.sequence } shouldContainExactlyInAnyOrder
            ids.flatMap { id -> (1L..3L).map { id to it } }
        fed.forEach { (database, events) -> events.all { journal.database(it.id) == database } shouldBe true }
        fed.values.forEach { events -> events.isNotEmpty() shouldBe true }
    }

    @Test
    fun `a conflict on Postgres is still a conflict`() {
        val id = PersistenceId("till", "t-1")
        journal.append(id, 0, listOf("1".toByteArray()))
        journal.append(id, 0, listOf("2".toByteArray())) shouldBe Either.Left(JournalConflict(id, 0, 1))
    }

    @Test
    fun `snapshots sit beside their events, and each database prunes on its own read models' offsets`() {
        val offsets = JdbcOffsets(a)
        val ids = (1..50).map { PersistenceId("till", "t-$it") }
        val inA = ids.first { journal.database(it) == "db-a" }
        val inB = ids.first { journal.database(it) == "db-b" }
        offsets.save(ShardedJournal.progress("totals", "db-a"), Long.MAX_VALUE)
        val prune = Prune.after(offsets, journal, "totals")

        flock<Nothing, Unit> {
            journal(journal)
            snapshots(ShardedSnapshots(listOf("db-a" to JdbcSnapshots(a), "db-b" to JdbcSnapshots(b))))
            // One payment at a time, so the snapshots fall at 10 and 20 whatever the timing.
            val tills = listOf(spawn("a", till(inA, prune, batch = 1)), spawn("b", till(inB, prune, batch = 1)))
            (1..25).forEach { paid -> tills.forEach { it.tell(paid) } }
            awaitIdle()
        }

        JdbcSnapshots(a).latest(inA)?.sequence shouldBe 20
        JdbcSnapshots(b).latest(inB)?.sequence shouldBe 20
        journal.read(inA).first().sequence shouldBe 11
        journal.read(inB).first().sequence shouldBe 1
    }
}
