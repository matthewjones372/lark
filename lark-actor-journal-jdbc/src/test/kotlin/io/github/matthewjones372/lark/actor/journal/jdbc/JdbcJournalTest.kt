package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.JournalContract
import io.github.matthewjones372.lark.actor.PersistenceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import java.sql.SQLException

class JdbcJournalTest : JournalContract() {
    override fun journal(): Journal = JdbcJournal(Postgres.fresh())

    @Test
    fun `a database that cannot answer throws from the append and the read, as a journal that cannot write does`() {
        val journal = JdbcJournal(Postgres.empty())
        val sam = PersistenceId("diary", "sam")

        shouldThrow<SQLException> { journal.append(sam, 0, listOf(byteArrayOf(1))) }.message shouldContain
            "lark_journal"
        shouldThrow<SQLException> { journal.read(sam) }
    }
}
