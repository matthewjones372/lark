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

class JdbcFeedGapTest {

    @Test
    fun `an append still open holds back what committed after it, until it commits`() {
        val source = database().migrated("h2")
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
            val source = database().migrated("h2")
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
    fun `a gap above everything read holds nothing back`() {
        val source = database().migrated("h2")
        val journal = JdbcJournal(source)
        journal.put("o-1")

        source.pending("o-2").use { journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1") }
    }
}

class JdbcPruneTest : PruneContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(database().migrated("h2"))
}
