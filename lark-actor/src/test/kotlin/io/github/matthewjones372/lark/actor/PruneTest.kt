package io.github.matthewjones372.lark.actor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

private val ledgerOf = PersistenceId("ledger", "l-1")

private val entry = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private val sum = object : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = state.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

/** Adds [amount] to the ledger, as one event. */
private data class Post(val amount: Int)

private fun ledger(prune: Boolean) = persistent<Post, Int, Long>(
    id = ledgerOf,
    empty = 0,
    codec = entry,
    snapshots = every(100, sum, prune = prune),
    command = { _, _, post -> persist(post.amount) },
    event = { total, amount -> total + amount },
)

class PruneTest {

    private val journal = InMemoryJournal()

    private fun kept() = journal.read(ledgerOf).map { it.sequence }

    @Test
    fun `with pruning, 1,050 events keep only 901 to 1,050, and a restart reaches the same state`() {
        testActors(journal = journal) {
            val book = spawn("ledger", ledger(prune = true))
            (1..1_050).forEach { book.send(Post(it)) }
            val before = book.state

            kept() shouldBe (901L..1_050L).toList()
            book.restart()

            book.state shouldBe before
        }
    }

    @Test
    fun `without pruning, every event is kept`() {
        testActors(journal = journal) {
            val book = spawn("ledger", ledger(prune = false))
            (1..250).forEach { book.send(Post(it)) }

            kept() shouldBe (1L..250L).toList()
        }
    }

    @Test
    fun `a snapshot that fails to save deletes nothing`() {
        val broken = object : SnapshotStore {
            override fun save(id: PersistenceId, sequence: Long, bytes: ByteArray) = error("the snapshot store is down")

            override fun latest(id: PersistenceId): Snapshot? = null
        }
        testActors(journal = journal, snapshots = broken) {
            val book = spawn("ledger", ledger(prune = true))
            (1..250).forEach { book.send(Post(it)) }

            kept() shouldBe (1L..250L).toList()
        }
    }

    @Test
    fun `a start that finds a pruned history and no snapshot fails, and names the gap`() {
        testActors(journal = journal) {
            val book = spawn("ledger", ledger(prune = true))
            (1..250).forEach { book.send(Post(it)) }
        }

        testActors(journal = journal, snapshots = InMemorySnapshots()) {
            val failed = shouldThrow<IllegalStateException> { spawn("ledger", ledger(prune = true)) }

            failed.message shouldContain "events 1 to 100 were deleted, and no snapshot covers them"
        }
    }
}
