package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.InMemoryOffsets
import io.github.matthewjones372.lark.actor.JournalUnavailable
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.ShardedJournal
import io.github.matthewjones372.lark.actor.SliceElsewhere
import io.github.matthewjones372.lark.actor.Slices
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Spec 0105 on Postgres: a range of slices moved from one database to another while appends go on. */
class SliceMoveTest {

    private val databases = listOf("db-0" to Postgres.fresh(), "db-1" to Postgres.fresh(), "db-2" to Postgres.fresh())
    private val primary = databases.first().second

    // A journal made on two databases, before spec 0105: the table is written from them. db-2 joins later.
    private fun journal(refreshEvery: Duration = 1.seconds) = ShardedJournal(
        databases.map { (name, source) -> name to JdbcJournal(source) },
        JdbcSliceTable(primary, listOf("db-0", "db-1"), refreshEvery),
    )

    private val ids = (1..1_000).map { PersistenceId("till", "t-$it") }
    private val moving = 0..255

    @Test
    fun `a journal's first start writes the formula's ranges, so every id stays where it was`() {
        val before = ShardedJournal(databases.take(2).map { (name, source) -> name to JdbcJournal(source) })
        val after = journal()
        ids.map(after::database) shouldContainExactly ids.map(before::database)
        journal().database(ids.first()) shouldBe before.database(ids.first())
    }

    @Test
    fun `under appends from a thousand ids, a range moves to a new database, losing none and pausing no other`() {
        val journal = journal()
        val appended = ConcurrentHashMap<PersistenceId, Long>()
        val refusedElsewhere = AtomicInteger()
        val stop = AtomicBoolean()
        val writers = (0 until 8).map { w ->
            Thread.ofPlatform().start {
                val mine = ids.filterIndexed { i, _ -> i % 8 == w }
                while (!stop.get()) {
                    mine.forEach { id ->
                        val last = appended[id] ?: 0
                        try {
                            journal.append(id, last, listOf("${last + 1}".toByteArray())).onRight { appended[id] = it }
                        } catch (unavailable: JournalUnavailable) {
                            if (Slices.of(id) !in moving) refusedElsewhere.incrementAndGet()
                        }
                    }
                }
            }
        }
        // Every id has events before the move, however long a round trip to the database takes.
        val warm = System.nanoTime() + 30.seconds.inWholeNanoseconds
        while (appended.size < ids.size && System.nanoTime() < warm) Thread.sleep(50)
        appended.size shouldBe ids.size
        val move = SliceMover(databases).move(moving, "db-2")
        Thread.sleep(1_000)
        stop.set(true)
        writers.forEach(Thread::join)

        move.source shouldBe "db-0"
        refusedElsewhere.get() shouldBe 0
        val moved = ids.filter { Slices.of(it) in moving }
        moved.isNotEmpty() shouldBe true
        moved.forEach { id -> journal().database(id) shouldBe "db-2" }
        ids.forEach { id ->
            withClue(id) {
                journal.read(id).map { String(it.bytes).toLong() } shouldContainExactly
                    (1..appended.getValue(id)).toList()
            }
        }
        moved.forEach { id -> JdbcJournal(databases[2].second).read(id).size.toLong() shouldBe appended[id] }
    }

    @Test
    fun `a node that has not seen the move is refused by the source, and appends on the target`() {
        val stale = journal(refreshEvery = 1.hours)
        val id = ids.first { Slices.of(it) in moving }
        stale.append(id, 0, listOf("1".toByteArray()))
        SliceMover(databases).move(moving, "db-2")

        shouldThrow<SliceElsewhere> { JdbcJournal(databases[0].second).append(id, 1, listOf("2".toByteArray())) }
        stale.append(id, 1, listOf("2".toByteArray())).isRight() shouldBe true
        JdbcJournal(databases[2].second).read(id).map { it.sequence } shouldContainExactly listOf(1L, 2L)
    }

    @Test
    fun `the source keeps a moved range until its read models have passed it, then lets it go`() {
        val journal = journal()
        ids.forEach { journal.append(it, 0, listOf("1".toByteArray())) }
        val snapshots = JdbcSnapshots(databases[0].second)
        ids.forEach { snapshots.save(it, 1, byteArrayOf(1)) }
        val move = SliceMover(databases).move(moving, "db-2")
        val offsets = InMemoryOffsets()
        val source = JdbcJournal(databases[0].second)
        val moved = ids.filter { Slices.of(it) in moving }
        moved.forEach { id -> JdbcSnapshots(databases[2].second).latest(id)?.sequence shouldBe 1 }

        offsets.save(ShardedJournal.progress("totals", "db-0"), move.copiedTo - 1)
        SliceMover(databases).cleanUp(offsets, listOf("totals"), after = Duration.ZERO).shouldBeEmpty()
        source.read(moved.first()).size shouldBe 1

        offsets.save(ShardedJournal.progress("totals", "db-0"), move.copiedTo)
        SliceMover(databases).cleanUp(offsets, listOf("totals"), after = Duration.ZERO) shouldContainExactly
            listOf(move)
        moved.forEach { id ->
            source.read(id).shouldBeEmpty()
            JdbcSnapshots(databases[0].second).latest(id) shouldBe null
        }
        // Every id left on the source is still in its feed, which reads past the rows deleted rather than waiting.
        source.after("till", 0, 10_000).map { it.id }.toSet() shouldBe
            ids.filter { journal().database(it) == "db-0" }.toSet()
    }
}
