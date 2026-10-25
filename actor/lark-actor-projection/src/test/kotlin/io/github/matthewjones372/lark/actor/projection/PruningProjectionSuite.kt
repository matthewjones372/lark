package io.github.matthewjones372.lark.actor.projection

import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.InMemoryJournal
import io.github.matthewjones372.lark.actor.InMemoryOffsets
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.Prune
import io.github.matthewjones372.lark.actor.StateCodec
import io.github.matthewjones372.lark.actor.every
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.actor.testActors
import io.github.matthewjones372.lark.junit.Expectations
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.start
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val amount = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private val total = object : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = state.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

private val ledgerId = PersistenceId("ledger", "l-1")

/** Posts [amount], as one event. */
private data class Post(val amount: Int)

private fun ledger(prune: Prune) = persistent<Post, Int, Long>(
    id = ledgerId,
    empty = 0,
    codec = amount,
    snapshots = every(100, total, prune = prune),
    command = { _, _, post -> persist(post.amount) },
    event = { sum, posted -> sum + posted },
)

object PruningProjectionSuite : Suite() {
    init {
        test("a projection that starts late still reads every event of a kind that prunes after it") {
            val journal = InMemoryJournal()
            val offsets = InMemoryOffsets()
            val handled = AtomicInteger()

            /** Runs the `totals` projection until it has handled [count] events in all, and stops it. */
            fun follow(count: Int): Expectations {
                val reached = CountDownLatch(1)
                val running = Projection.follow(journal, "ledger", amount, offsets, "totals")
                    .mapFollowed { event ->
                        event.value.also { if (handled.incrementAndGet() == count) reached.countDown() }
                    }
                    .runProjecting()
                    .start(Forks())
                val stopped = reached.await(1, TimeUnit.MINUTES) shouldBe true
                running.close()
                return stopped
            }

            val followed = testActors(journal = journal) {
                val book = spawn("ledger", ledger(Prune.after(offsets, "totals")))
                (1..1_050).forEach { book.send(Post(it)) }
                val appended = journal.read(ledgerId).size shouldBe 1_050

                val first = follow(1_050)

                (1_051..1_150).forEach { book.send(Post(it)) }
                appended and first and (journal.read(ledgerId).first().sequence shouldBe 1_001) and follow(1_150)
            }

            followed and (handled.get() shouldBe 1_150)
        }
    }
}
