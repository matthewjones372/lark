package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.actor.FeedContract
import io.github.matthewjones372.lark.actor.FeedEvent
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.PruneContract
import io.github.matthewjones372.lark.clock
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

class JdbcFeedTest : FeedContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(database().migrated("h2"))
}

private fun List<FeedEvent>.ids() = map { "${it.id.id}#${it.sequence}" }

/** An append that has taken its place in the feed and not yet committed, on a connection of its own. */
private fun DataSource.pending(id: String): Connection = connection.apply {
    autoCommit = false
    prepareStatement("insert into lark_journal (kind, id, seq_nr, bytes) values ('order', ?, 1, ?)").use {
        it.setString(1, id)
        it.setBytes(2, "pending".toByteArray())
        it.executeUpdate()
    }
}

private fun JdbcJournal.put(id: String) = append(PersistenceId("order", id), 0, listOf("placed".toByteArray()))

/**
 * What the JDBC feed does at a gap, on whichever database [source] gives: an append still open on one connection holds
 * the feed back on another, and one that never commits is passed after `gapTimeout`.
 */
abstract class FeedGaps {

    /** An empty database with the journal's table, fresh for each test. */
    abstract fun source(): DataSource

    @Test
    fun `an append still open holds back what committed after it, until it commits`() {
        val source = source()
        val journal = JdbcJournal(source)
        journal.put("o-1")
        val open = source.pending("o-2")
        journal.put("o-3")

        journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")

        open.use { it.commit() }
        journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1", "o-2#1", "o-3#1")
    }

    @Test
    fun `a gap that does not fill within gapTimeout is passed, and what is after it is read`() {
        val moving = TestClock()
        clock.locally(moving) {
            val source = source()
            val journal = JdbcJournal(source, gapTimeout = 10.seconds)
            journal.put("o-1")
            source.pending("o-2").use { open ->
                journal.put("o-3")
                journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")

                moving.adjust(9.seconds)
                journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")

                open.rollback()
                moving.adjust(1.seconds)
                journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1", "o-3#1")
            }
        }
    }

    @Test
    fun `gaps seen together are passed together, after one gapTimeout and not one each`() {
        val moving = TestClock()
        clock.locally(moving) {
            val source = source()
            val journal = JdbcJournal(source, gapTimeout = 10.seconds)
            journal.put("o-0")
            // Five appends that each took a place in the feed and rolled back, as a database stopping mid-write does.
            (1..5).forEach { n ->
                source.pending("gone-$n").use { open -> journal.put("o-$n").also { open.rollback() } }
            }
            journal.after("order", 0, 20).ids() shouldContainExactly listOf("o-0#1")

            moving.adjust(10.seconds)
            journal.after("order", 0, 20).ids() shouldContainExactly (0..5).map { "o-$it#1" }
        }
    }

    @Test
    fun `a gap above everything read holds nothing back`() {
        val source = source()
        val journal = JdbcJournal(source)
        journal.put("o-1")

        source.pending("o-2").use { journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1") }
    }
}

class JdbcFeedGapTest : FeedGaps() {
    override fun source(): DataSource = database().migrated("h2")
}

class PostgresFeedGapTest : FeedGaps() {
    override fun source(): DataSource = Postgres.fresh()

    @Test
    fun `an append whose writer is slow to commit is waited for past gapTimeout, and read once it commits`() {
        val moving = TestClock()
        clock.locally(moving) {
            val source = source()
            val journal = JdbcJournal(source, gapTimeout = 10.seconds)
            journal.put("o-1")
            source.pending("o-2").use { open ->
                journal.put("o-3")
                journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")

                // A writer frozen mid-commit, as a paused JVM is: its transaction is still running, so the gap holds.
                moving.adjust(30.seconds)
                journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")

                open.commit()
                journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1", "o-2#1", "o-3#1")
            }
        }
    }

    @Test
    fun `a rolled back append's gap is passed once its transaction has ended, well inside gapTimeout`() {
        val moving = TestClock()
        clock.locally(moving) {
            val source = source()
            val journal = JdbcJournal(source, gapTimeout = 10.seconds)
            journal.put("o-1")
            source.pending("o-2").use { open ->
                journal.put("o-3")
                journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")
                open.rollback()
            }
            moving.adjust(2.seconds)
            journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1", "o-3#1")
        }
    }
}

class JdbcPruneTest : PruneContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(database().migrated("h2"))
}
