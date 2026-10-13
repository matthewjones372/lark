package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.PersistenceId
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/** The journal's tables as a Liquibase changelog (spec 0107). */
class ChangelogTest {

    @Test
    fun `a fresh database takes the changelog once, with every string column text`() {
        val source = Postgres.empty()
        // The tables, then the metadata column (spec 0123).
        source.migrate() shouldBe 2
        source.migrate() shouldBe 0
        JdbcJournal(source).append(PersistenceId("diary", "sam"), 0, listOf(byteArrayOf(1)))
        source.stringColumns() shouldContainOnly setOf("text")
    }

    /** The type of every column of lark's tables that holds a string. */
    private fun DataSource.stringColumns(): Set<String> = connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                select distinct data_type from information_schema.columns
                where table_name like 'lark\_%' and data_type in ('text', 'character varying')
                """,
            ).use { rows -> generateSequence { if (rows.next()) rows.getString(1) else null }.toSet() }
        }
    }
}
