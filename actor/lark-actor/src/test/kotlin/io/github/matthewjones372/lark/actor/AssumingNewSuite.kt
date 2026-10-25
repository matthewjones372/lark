package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldContainExactly
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

private val tallyCodec = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private sealed interface GuessCommand

private data class Plus(val n: Int) : GuessCommand

private data class Sum(val reply: Reply<Int>) : GuessCommand

private fun tallyOf(id: String) = PersistenceId("tally", id)

/**
 * A running total remembered by its events, started with [start]. Each `then` and each start hook is written to
 * [log], the hook with the total its start left.
 */
private fun tally(
    id: String,
    start: Start,
    log: ConcurrentLinkedQueue<String>,
): Behaviour<GuessCommand, Remembered<Int>, JournalConflict> {
    val persistent = persistent<GuessCommand, Int, Int>(
        id = tallyOf(id),
        empty = 0,
        codec = tallyCodec,
        command = { _, total, command ->
            when (command) {
                is Plus -> persist(command.n).then { after -> log += "added ${command.n} to make $after" }
                is Sum -> none().then { command.reply(total) }
            }
        },
        event = Int::plus,
        start = start,
    )
    return Behaviour(persistent.initial, persistent.step, persistent.signal, { ctx, state ->
        val next = persistent.start?.invoke(this, ctx, state) ?: Next.Stay
        log += "started at ${((next as? Next.Become)?.state ?: state).value}"
        next
    })
}

/** A journal that counts its reads. */
private class ReadCounting(private val inner: Journal = InMemoryJournal()) : Journal by inner {
    val reads = AtomicInteger()

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = inner.read(id, from).also {
        reads.incrementAndGet()
    }
}

/** Spec 0198: a persistent actor started assuming it is new reads its journal only when that guess is wrong. */
object AssumingNewSuite : Suite() {
    init {
        test("a new entity's first command persists without a read, and its start hooks see the empty state") {
            val journal = ReadCounting()
            val log = ConcurrentLinkedQueue<String>()
            val total = testActors(journal = journal) {
                val ada = spawn("ada", tally("ada", Start.assumingNew, log))
                ada.send(Plus(5))
                ada.send(Plus(2))
                ada.ask { Sum(it) }
            }

            (total shouldBe 7.right()) and
                (journal.reads.get() shouldBe 0) and
                (log.toList() shouldContainExactly listOf("started at 0", "added 5 to make 5", "added 2 to make 7"))
        }

        test("an entity that existed recovers once its first append conflicts, and decides that command again") {
            val journal = ReadCounting()
            journal.append(tallyOf("ada"), 0, listOf(tallyCodec.encode(40)))
            val log = ConcurrentLinkedQueue<String>()
            val (total, restarts) = testActors(journal = journal) {
                val ada = spawn("ada", tally("ada", Start.assumingNew, log))
                ada.send(Plus(2))
                ada.ask { Sum(it) } to ada.restarts
            }

            // The guess's `then` never ran; the hooks ran again with what was recovered; supervision was not asked.
            (total shouldBe 42.right()) and
                (restarts shouldBe 0) and
                (journal.events(tallyOf("ada"), tallyCodec) shouldContainExactly listOf(40, 2)) and
                (log.toList() shouldContainExactly listOf("started at 0", "started at 40", "added 2 to make 42"))
        }

        test("a first command that persists nothing is answered from the recovered state, not the empty one") {
            val journal = ReadCounting()
            journal.append(tallyOf("ada"), 0, listOf(tallyCodec.encode(40)))
            val log = ConcurrentLinkedQueue<String>()
            val total = testActors(journal = journal) {
                spawn("ada", tally("ada", Start.assumingNew, log)).ask { Sum(it) }
            }

            (total shouldBe 40.right()) and
                (journal.reads.get() shouldBe 1) and
                (log.toList() shouldContainExactly listOf("started at 0", "started at 40"))
        }

        test("recovering, the default, reads the journal before the first command as before") {
            val journal = ReadCounting()
            val log = ConcurrentLinkedQueue<String>()
            testActors(journal = journal) {
                val ada = spawn("ada", tally("ada", Start.recovering, log))
                ada.send(Plus(5))
            }

            journal.reads.get() shouldBe 1
        }

        test("on threads, a new entity reads nothing, and one that existed ends where its events say") {
            val journal = ReadCounting()
            journal.append(tallyOf("old"), 0, listOf(tallyCodec.encode(40)))
            val log = ConcurrentLinkedQueue<String>()
            val totals = flock<Nothing, Any> {
                journal(journal)
                val fresh = spawn("fresh", tally("fresh", Start.assumingNew, log))
                val old = spawn("old", tally("old", Start.assumingNew, log))
                fresh.tell(Plus(5))
                old.tell(Plus(2))
                listOf(fresh.ask(1.minutes) { Sum(it) }, old.ask(1.minutes) { Sum(it) })
            }

            (totals shouldBe listOf(5.right(), 42.right()).right()) and
                (journal.reads.get() shouldBe 1) and
                (journal.events(tallyOf("old"), tallyCodec) shouldContainExactly listOf(40, 2))
        }
    }
}
