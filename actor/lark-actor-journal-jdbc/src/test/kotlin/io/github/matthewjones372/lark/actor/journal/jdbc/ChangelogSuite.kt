package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.resource
import javax.sql.DataSource

/** The journal's tables as a Liquibase changelog (spec 0107). */
object ChangelogSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a fresh database takes the changelog once, with every string column text") { _ ->
            val source = Postgres.empty()
            // The tables, then the metadata column (spec 0123).
            val first = source.migrate() shouldBe 2
            val again = source.migrate() shouldBe 0
            JdbcJournal(source).append(PersistenceId("diary", "sam"), 0, listOf(byteArrayOf(1)))
            first and again and (source.stringColumns() shouldBe setOf("text"))
        }
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
