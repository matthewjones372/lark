package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.JournalContract
import io.github.matthewjones372.lark.actor.PersistenceId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.string.shouldContain
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

/** An H2 database in memory that lasts as long as the JVM, empty, with a name no other test uses. */
internal fun database(): DataSource = JdbcDataSource().apply {
    setURL("jdbc:h2:mem:journal-${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
}

/** The DDL the jar ships for [database], as a service would apply it. */
internal fun DataSource.migrated(database: String): DataSource = also {
    val ddl = checkNotNull(JdbcJournal::class.java.getResource("/lark/journal/jdbc/$database.sql")).readText()
    connection.use { connection -> connection.createStatement().use { statement -> statement.execute(ddl) } }
}

class JdbcJournalTest : JournalContract() {
    override fun journal(): Journal = JdbcJournal(database().migrated("h2"))

    @Test
    fun `a database that cannot answer throws from the append and the read, as a journal that cannot write does`() {
        val journal = JdbcJournal(database())
        val sam = PersistenceId("diary", "sam")

        shouldThrow<SQLException> { journal.append(sam, 0, listOf(byteArrayOf(1))) }.message shouldContain
            "LARK_JOURNAL"
        shouldThrow<SQLException> { journal.read(sam) }
    }

    @Test
    fun `the jar ships its table for Postgres beside H2's`() {
        JdbcJournal::class.java.getResource("/lark/journal/jdbc/postgres.sql").shouldNotBeNull()
    }
}
