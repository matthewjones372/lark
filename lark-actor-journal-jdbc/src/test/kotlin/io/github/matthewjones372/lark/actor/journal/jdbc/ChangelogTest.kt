package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.PersistenceId
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import javax.sql.DataSource

/** The journal's tables as Liquibase changelogs, on each database they are written for. */
class ChangelogTest {

    @Test
    fun `a fresh H2 database takes every changeset once`() = once(database(), "h2")

    @Test
    fun `a fresh Postgres database takes every changeset once`() = once(Postgres.empty(), "postgres")

    @Test
    fun `a Postgres journal made before slices takes the changelog up and keeps its events`() {
        val source = Postgres.empty()
        source.execute(BEFORE_SLICES)
        val sam = PersistenceId("diary", "sam")
        source.execute("insert into lark_journal (kind, id, seq_nr, bytes) values ('diary', 'sam', 1, '\\x01')")

        source.migrate("postgres") shouldBe CHANGESETS
        val journal = JdbcJournal(source)
        journal.fillSlices() shouldBe 1
        journal.read(sam, 1).map { it.bytes.toList() } shouldBe listOf(listOf<Byte>(1))
        journal.append(sam, 1, listOf(byteArrayOf(2)))
        journal.read(sam, 1).size shouldBe 2
    }

    private fun once(source: DataSource, database: String) {
        source.migrate(database) shouldBe CHANGESETS
        source.migrate(database) shouldBe 0
        JdbcJournal(source).append(PersistenceId("diary", "sam"), 0, listOf(byteArrayOf(1)))
    }

    private fun DataSource.execute(sql: String) =
        connection.use { connection -> connection.createStatement().use { statement -> statement.execute(sql) } }

    private companion object {
        const val CHANGESETS = 3

        /** What postgres.sql created before spec 0105, applied as it then was: by hand, with no changelog. */
        const val BEFORE_SLICES = """
            create table lark_journal (
                kind varchar(255) not null, id varchar(255) not null, seq_nr bigint not null, bytes bytea not null,
                ordering bigint generated always as identity, primary key (kind, id, seq_nr)
            );
            create unique index lark_journal_ordering on lark_journal (ordering);
            create index lark_journal_kind_ordering on lark_journal (kind, ordering);
            create table lark_journal_pruned (from_ordering bigint not null, to_ordering bigint not null);
            create index lark_journal_pruned_to on lark_journal_pruned (to_ordering);
            create table lark_snapshot (
                kind varchar(255) not null, id varchar(255) not null, seq_nr bigint not null, bytes bytea not null,
                primary key (kind, id)
            );
            create table lark_offset (name varchar(255) not null, last_ordering bigint not null, primary key (name));
        """
    }
}
