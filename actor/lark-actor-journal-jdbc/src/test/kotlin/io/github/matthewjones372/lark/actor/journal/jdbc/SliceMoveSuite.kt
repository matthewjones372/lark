package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.actor.InMemoryOffsets
import io.github.matthewjones372.lark.actor.JournalUnavailable
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.ShardedJournal
import io.github.matthewjones372.lark.actor.SliceElsewhere
import io.github.matthewjones372.lark.actor.Slices
import io.github.matthewjones372.lark.fail.onRight
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.forAll
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeEmpty
import io.github.matthewjones372.lark.junit.shouldContainExactly
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

private val ids = (1..1_000).map { PersistenceId("till", "t-$it") }
private val moving = 0..255

/** Three fresh databases for one test. */
private class ThreeDatabases {
    val databases = listOf("db-0" to Postgres.fresh(), "db-1" to Postgres.fresh(), "db-2" to Postgres.fresh())
    val primary = databases.first().second

    // A journal made on two databases, before spec 0105: the table is written from them. db-2 joins later.
    fun journal(refreshEvery: Duration = 1.seconds) = ShardedJournal(
        databases.map { (name, source) -> name to JdbcJournal(source) },
        JdbcSliceTable(primary, listOf("db-0", "db-1"), refreshEvery),
    )
}

/** Spec 0105 on Postgres: a range of slices moved from one database to another while appends go on. */
object SliceMoveSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a journal's first start writes the formula's ranges, so every id stays where it was") { _ ->
            with(ThreeDatabases()) {
                val before = ShardedJournal(databases.take(2).map { (name, source) -> name to JdbcJournal(source) })
                val after = journal()
                (ids.map(after::database) shouldContainExactly ids.map(before::database)) and
                    (journal().database(ids.first()) shouldBe before.database(ids.first()))
            }
        }

        test(
            "under appends from a thousand ids, a range moves to a new database, losing none and pausing no other",
        ) { _ ->
            with(ThreeDatabases()) {
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
                                    journal.append(id, last, listOf("${last + 1}".toByteArray()))
                                        .onRight { appended[id] = it }
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
                val warmed = appended.size shouldBe ids.size
                val move = SliceMover(databases).move(moving, "db-2")
                Thread.sleep(1_000)
                stop.set(true)
                writers.forEach(Thread::join)

                val moved = ids.filter { Slices.of(it) in moving }
                warmed and
                    (move.source shouldBe "db-0") and
                    (refusedElsewhere.get() shouldBe 0) and
                    (moved.isNotEmpty() shouldBe true) and
                    moved.forAll { id -> journal().database(id) shouldBe "db-2" } and
                    ids.forAll { id ->
                        withClue(id) {
                            journal.read(id).map { String(it.bytes).toLong() } shouldContainExactly
                                (1..appended.getValue(id)).toList()
                        }
                    } and
                    moved.forAll { id ->
                        JdbcJournal(databases[2].second).read(id).size.toLong() shouldBe appended[id]
                    }
            }
        }

        test("a node that has not seen the move is refused by the source, and appends on the target") { _ ->
            with(ThreeDatabases()) {
                val stale = journal(refreshEvery = 1.hours)
                val id = ids.first { Slices.of(it) in moving }
                stale.append(id, 0, listOf("1".toByteArray()))
                SliceMover(databases).move(moving, "db-2")

                shouldThrow<SliceElsewhere> {
                    JdbcJournal(databases[0].second).append(id, 1, listOf("2".toByteArray()))
                } and
                    (stale.append(id, 1, listOf("2".toByteArray())).isRight() shouldBe true) and
                    (
                        JdbcJournal(databases[2].second).read(id).map { it.sequence } shouldContainExactly
                            listOf(1L, 2L)
                        )
            }
        }

        test("the source keeps a moved range until its read models have passed it, then lets it go") { _ ->
            with(ThreeDatabases()) {
                val journal = journal()
                ids.forEach { journal.append(it, 0, listOf("1".toByteArray())) }
                val snapshots = JdbcSnapshots(databases[0].second)
                ids.forEach { snapshots.save(it, 1, byteArrayOf(1)) }
                val move = SliceMover(databases).move(moving, "db-2")
                val offsets = InMemoryOffsets()
                val source = JdbcJournal(databases[0].second)
                val moved = ids.filter { Slices.of(it) in moving }
                val copied = moved.forAll { id -> JdbcSnapshots(databases[2].second).latest(id)?.sequence shouldBe 1 }

                offsets.save(ShardedJournal.progress("totals", "db-0"), move.copiedTo - 1)
                val kept = SliceMover(databases).cleanUp(offsets, listOf("totals"), after = Duration.ZERO)
                    .shouldBeEmpty() and
                    (source.read(moved.first()).size shouldBe 1)

                offsets.save(ShardedJournal.progress("totals", "db-0"), move.copiedTo)
                val letGo = SliceMover(databases).cleanUp(offsets, listOf("totals"), after = Duration.ZERO)
                    .shouldContainExactly(listOf(move))
                copied and kept and letGo and
                    moved.forAll { id ->
                        source.read(id).shouldBeEmpty() and
                            (JdbcSnapshots(databases[0].second).latest(id) shouldBe null)
                    } and
                    // Every id left on the source is still in its feed, which reads past the rows deleted rather
                    // than waiting.
                    (
                        source.after("till", 0, 10_000).map { it.id }.toSet() shouldBe
                            ids.filter { journal().database(it) == "db-0" }.toSet()
                        )
            }
        }
    }
}
