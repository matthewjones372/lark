package io.github.matthewjones372.lark.actor.journal.jdbc

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.actor.FeedEvent
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.JournalFeed
import io.github.matthewjones372.lark.actor.JournalPruning
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.StoredEvent
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.logWarn
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

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
 *
 * It is a [JournalFeed] too (spec 0075), its offsets the table's `ordering`. An append takes its `ordering` when it
 * inserts and is seen when it commits, so a smaller one can commit after a larger one is read: the feed answers
 * nothing past a missing `ordering` until it fills, or until it has been missing for [gapTimeout], after which it is
 * taken for an append that never committed and passed, with a warning.
 */
class JdbcJournal(private val dataSource: DataSource, private val gapTimeout: Duration = 10.seconds) :
    Journal,
    JournalFeed,
    JournalPruning {

    private val time = clock.get()

    /** Each `ordering` found missing, and when it was first found so. */
    private val gaps = ConcurrentHashMap<Long, Instant>()

    /** The `ordering`s missing for longer than [gapTimeout], which the feed reads past. */
    private val passed = ConcurrentHashMap.newKeySet<Long>()

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

    override fun after(kind: String, offset: Long, limit: Int): List<FeedEvent> = dataSource.connection.use {
            connection ->
        val rows = connection.statement(
            "select ordering, id, seq_nr, bytes from lark_journal where kind = ? and ordering > ? " +
                "order by ordering fetch first ? rows only",
            kind,
            offset,
            limit,
        ) { select ->
            select.executeQuery().use { rows ->
                generateSequence {
                    if (!rows.next()) return@generateSequence null
                    val id = PersistenceId(kind, rows.getString(2))
                    FeedEvent(rows.getLong(1), id, rows.getLong(3), rows.getBytes(4))
                }.toList()
            }
        }
        val held = rows.lastOrNull()?.let { connection.heldBack(kind, offset, rows) } ?: return@use rows
        rows.takeWhile { it.offset < held }
    }

    /**
     * Deletes [id]'s events up to [sequence], never its newest, and records the orderings the deleted rows spanned in
     * `lark_journal_pruned`, in the same transaction, so the feed reads past them rather than waiting on them as gaps.
     */
    override fun deleteTo(id: PersistenceId, sequence: Long) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prune(id, minOf(sequence, connection.last(id) - 1))
                connection.commit()
            } catch (failed: SQLException) {
                connection.rollback()
                throw failed
            }
        }
    }

    private fun Connection.prune(id: PersistenceId, upTo: Long) {
        val span = statement(
            "select min(ordering), max(ordering) from lark_journal where kind = ? and id = ? and seq_nr <= ?",
            id.kind,
            id.id,
            upTo,
        ) { select ->
            select.executeQuery().use { rows ->
                rows.next()
                val first = rows.getLong(1)
                if (rows.wasNull()) null else first to rows.getLong(2)
            }
        } ?: return
        statement("delete from lark_journal where kind = ? and id = ? and seq_nr <= ?", id.kind, id.id, upTo) {
            it.executeUpdate()
        }
        val (from, to) = span
        statement("insert into lark_journal_pruned (from_ordering, to_ordering) values (?, ?)", from, to) {
            it.executeUpdate()
        }
    }

    /**
     * The first `ordering` after [offset] and below the last of [read] that the feed cannot yet read past: one missing
     * for less than [gapTimeout], or one of [kind] that committed after [read] was selected and is not in it. Null
     * when there is none.
     */
    private fun Connection.heldBack(kind: String, offset: Long, read: List<FeedEvent>): Long? {
        val top = read.last().offset
        val present = statement(
            "select ordering, kind from lark_journal where ordering > ? and ordering < ?",
            offset,
            top,
        ) { select ->
            select.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.getLong(1) to rows.getString(2) else null }.toMap()
            }
        }
        // Read after the rows: a deletion commits its rows' removal and its span together, so a row missing here
        // was either never committed or is inside a span this reads.
        val pruned = statement(
            "select from_ordering, to_ordering from lark_journal_pruned where to_ordering > ? and from_ordering < ?",
            offset,
            top,
        ) { select ->
            select.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.getLong(1)..rows.getLong(2) else null }.toList()
            }
        }
        val seen = read.mapTo(HashSet()) { it.offset }
        val now = time.now()
        return (offset + 1 until top).firstOrNull { ordering ->
            val of = present[ordering]
            when {
                of != null -> (of == kind && ordering !in seen).also { gaps.remove(ordering) }

                ordering in passed || pruned.any { ordering in it } -> false

                else -> {
                    val waited = java.time.Duration.between(gaps.computeIfAbsent(ordering) { now }, now)
                    val holding = waited.toKotlinDuration() < gapTimeout
                    if (!holding && passed.add(ordering)) {
                        gaps.remove(ordering)
                        logWarn("the feed passes ordering $ordering, missing for $waited: an append never committed")
                    }
                    holding
                }
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
