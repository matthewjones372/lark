package io.github.matthewjones372.surefold.actor

import io.github.matthewjones372.surefold.LogLevel
import io.github.matthewjones372.surefold.LogLine
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeNull
import io.github.matthewjones372.surefold.junit.shouldContainExactly
import io.github.matthewjones372.surefold.junit.shouldNotBeNull
import io.github.matthewjones372.surefold.logger
import java.util.concurrent.ConcurrentLinkedQueue

private val tally = PersistenceId("tally", "t-1")

private val count = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

/** A tally's state: the sum of what was added, and how many additions made it. */
private data class Tally(val sum: Long = 0, val adds: Int = 0)

private val tallyCodec = object : StateCodec<Tally> {
    override fun encode(state: Tally): ByteArray = "${state.sum},${state.adds}".toByteArray()

    override fun decode(bytes: ByteArray): Tally = String(bytes).split(",").let { (sum, adds) ->
        Tally(sum.toLong(), adds.toInt())
    }
}

private sealed interface Counting

/** Adds each of [amounts], one event apiece. */
private class Tallied(vararg val amounts: Int) : Counting

private data class Total(val reply: Reply<Tally>) : Counting

private fun tallying(snapshots: Snapshotting<Tally>?) = persistent<Counting, Int, Tally>(
    id = tally,
    empty = Tally(),
    codec = count,
    snapshots = snapshots,
    command = { _, now, command ->
        when (command) {
            is Tallied -> persist(*command.amounts.toTypedArray())
            is Total -> none().then { command.reply(now) }
        }
    },
    event = { now, added -> Tally(now.sum + added, now.adds + 1) },
)

/** A journal that remembers every read: where it began, and how many events it answered. */
private class Reading(private val kept: Journal = InMemoryJournal()) : Journal by kept {
    val reads = ConcurrentLinkedQueue<Pair<Long, Int>>()

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> =
        kept.read(id, from).also { reads += from to it.size }
}

object SnapshotSuite : Suite() {
    init {
        test("a restart after 1,050 events starts from the snapshot at 1,000 and reads the 50 after it") {
            val journal = Reading()
            testActors(journal = journal) {
                val counter = spawn("tally", tallying(every(100, tallyCodec)))
                (1..1_050).forEach { counter.send(Tallied(it)) }
                val before = counter.state

                journal.reads.clear()
                counter.restart()

                (counter.state shouldBe before) and
                    snapshots.latest(tally).shouldNotBeNull { it.sequence shouldBe 1_000 } and
                    (journal.reads.toList() shouldContainExactly listOf(1_001L to 50)) and
                    // The same history replayed from the start, with no snapshot, builds the same state.
                    (spawn("replayed", tallying(null)).state shouldBe before)
            }
        }

        test("a persist that carries the count across a multiple takes a snapshot at the count it reaches") {
            testActors {
                val counter = spawn("tally", tallying(every(100, tallyCodec)))
                counter.send(Tallied(*IntArray(98) { 1 }))
                val noneYet = snapshots.latest(tally).shouldBeNull()

                counter.send(Tallied(1, 1, 1))

                noneYet and
                    snapshots.latest(tally).shouldNotBeNull { taken ->
                        (taken.sequence shouldBe 101) and
                            (tallyCodec.decode(taken.bytes) shouldBe Tally(sum = 101, adds = 101))
                    }
            }
        }

        test("a store whose saves throw leaves every command answered, and says so") {
            val lines = ConcurrentLinkedQueue<LogLine>()
            val broken = object : SnapshotStore {
                override fun save(id: PersistenceId, sequence: Long, bytes: ByteArray) =
                    error("the snapshot store is down")

                override fun latest(id: PersistenceId): Snapshot? = null
            }
            val answered = logger.locally({ line -> lines += line }) {
                testActors(snapshots = broken) {
                    val counter = spawn("tally", tallying(every(10, tallyCodec)))

                    val answers = (1..25).map {
                        counter.send(Tallied(1))
                        counter.ask<Tally> { reply -> Total(reply) }
                    }

                    (answers.last() shouldBe Tally(sum = 25, adds = 25).right()) and
                        (answers.all(Either<AskFailure, Tally>::isRight) shouldBe true) and
                        counter.failure.shouldBeNull()
                }
            }
            val errors = lines.filter { it.level == LogLevel.Error }.map { it.cause?.message }
            answered and
                (errors shouldContainExactly listOf("the snapshot store is down", "the snapshot store is down"))
        }

        test("a behaviour with no snapshotting takes none, even with a store") {
            testActors {
                val counter = spawn("tally", tallying(null))

                (1..150).forEach { counter.send(Tallied(it)) }

                snapshots.latest(tally).shouldBeNull()
            }
        }
    }
}
