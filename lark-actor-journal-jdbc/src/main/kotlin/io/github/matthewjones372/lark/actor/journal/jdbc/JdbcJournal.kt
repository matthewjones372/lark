package io.github.matthewjones372.lark.actor.journal.jdbc

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.StoredEvent
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.sql.DataSource

/** The SQL state every database gives a duplicate key: another writer took the sequence number first. */
internal const val DUPLICATE_KEY = "23505"

/**
 * A [Journal] in one `lark_journal` table, reached through [dataSource], so that every node that reaches the same
 * database reads the same events. The table's DDL ships in the jar, as `lark/journal/jdbc/postgres.sql` and
 * `lark/journal/jdbc/h2.sql`; the service applies it, and nothing here creates it.
 *
 * Of two writers for one id, the table's primary key decides: an append inserts its first event only if the one it
 * expects to follow is there, and two that both expect it collide on the key, so exactly one commits. A database
 * that cannot answer throws its [SQLException].
 */
class JdbcJournal(private val dataSource: DataSource) : Journal {

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            val appended = try {
                connection.insert(id, expected, events)
            } catch (failed: SQLException) {
                connection.rollback()
                if (failed.sqlState != DUPLICATE_KEY) throw failed
                false
            }
            if (appended) {
                connection.commit()
                (expected + events.size).right()
            } else {
                connection.rollback()
                JournalConflict(id, expected, connection.last(id)).left()
            }
        }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = dataSource.connection.use { connection ->
        connection.statement(
            "select seq_nr, bytes from lark_journal where kind = ? and id = ? and seq_nr >= ? order by seq_nr",
            id.kind,
            id.id,
            from,
        ) { select ->
            select.executeQuery().use { rows ->
                generateSequence { if (rows.next()) StoredEvent(rows.getLong(1), rows.getBytes(2)) else null }.toList()
            }
        }
    }

    /**
     * Inserts [events] after [expected], the first only if the event it follows is there: whether they all went in.
     * A writer ahead of the journal inserts nothing, and one behind it collides on the key.
     */
    private fun Connection.insert(id: PersistenceId, expected: Long, events: List<ByteArray>): Boolean {
        if (events.isEmpty()) return last(id) == expected
        val first = statement(
            "insert into lark_journal (kind, id, seq_nr, bytes) select ?, ?, ?, ? from (values (1)) " +
                "where ? = 0 or exists (select 1 from lark_journal where kind = ? and id = ? and seq_nr = ?)",
            id.kind,
            id.id,
            expected + 1,
            events.first(),
            expected,
            id.kind,
            id.id,
            expected,
        ) { it.executeUpdate() }
        if (first == 0) return false
        events.drop(1).forEachIndexed { i, bytes ->
            statement(
                "insert into lark_journal (kind, id, seq_nr, bytes) values (?, ?, ?, ?)",
                id.kind,
                id.id,
                expected + i + 2,
                bytes,
            ) { it.executeUpdate() }
        }
        return true
    }

    private fun Connection.last(id: PersistenceId): Long = statement(
        "select coalesce(max(seq_nr), 0) from lark_journal where kind = ? and id = ?",
        id.kind,
        id.id,
    ) { select ->
        select.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }
}

/** [sql] with [values] bound to its parameters in order, handed to [use] and closed after. */
internal fun <T> Connection.statement(sql: String, vararg values: Any, use: (PreparedStatement) -> T): T =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
        use(statement)
    }
