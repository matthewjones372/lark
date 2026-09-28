package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.SliceMap
import io.github.matthewjones372.lark.actor.SliceTable
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The slice map in `lark_journal_slices`, in the first database, [primary] (spec 0105). A journal whose table is
 * empty is given the ranges spec 0088's formula gives [databases], as version 1, so one created before this spec
 * routes every id where it did. The newest version is read at most every [refreshEvery], and at once on [refresh].
 */
class JdbcSliceTable(
    private val primary: DataSource,
    private val databases: List<String>,
    private val refreshEvery: Duration = 1.seconds,
) : SliceTable {
    private val time = TimeSource.Monotonic

    @Volatile
    private var held: Pair<SliceMap, TimeSource.Monotonic.ValueTimeMark>? = null

    override fun current(): SliceMap {
        val (map, read) = held ?: return refresh()
        return if (read.elapsedNow() < refreshEvery) map else refresh()
    }

    @Synchronized
    override fun refresh(): SliceMap {
        val map = primary.connection.use { connection ->
            val newest = connection.newest()
            val held = held?.first
            when {
                held != null && held.version == newest -> held
                newest != null -> connection.load(newest)
                else -> connection.first()
            }
        }
        held = map to time.markNow()
        return map
    }

    /** Writes the formula's ranges as version 1; another node writing them first is as good. */
    private fun Connection.first(): SliceMap {
        val map = SliceMap.even(databases)
        try {
            write(map)
        } catch (taken: SQLException) {
            if (!taken.duplicate()) throw taken
        }
        return load(checkNotNull(newest()))
    }

    private fun Connection.newest(): Long? = statement("select max(version) from lark_journal_slices") { select ->
        select.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1).takeUnless { rows.wasNull() }
        }
    }

    @Suppress("MagicNumber") // column indices
    private fun Connection.load(version: Long): SliceMap = SliceMap(
        version,
        statement(
            "select from_slice, to_slice, owner from lark_journal_slices where version = ? order by from_slice",
            version,
        ) { select ->
            select.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.getInt(1)..rows.getInt(2) to rows.getString(3) else null }
                    .toList()
            }
        },
    )
}

/** Writes [map]'s ranges under its version, in one transaction: a version already written fails on its key. */
internal fun Connection.write(map: SliceMap) = transaction { insertMap(map) }

/** Inserts [map]'s ranges under its version, in the transaction already open. */
internal fun Connection.insertMap(map: SliceMap) {
    prepareStatement("insert into lark_journal_slices (version, from_slice, to_slice, owner) values (?, ?, ?, ?)")
        .use { insert ->
            map.ranges.forEach { (slices, owner) -> insert.row(map.version, slices.first, slices.last, owner) }
            insert.executeBatch()
        }
}

/** [block] in one transaction: committed if it returns, rolled back if it throws. */
@Suppress("TooGenericExceptionCaught") // rethrown, whatever it is, once rolled back
internal fun <T> Connection.transaction(block: Connection.() -> T): T {
    val auto = autoCommit
    autoCommit = false
    return try {
        block().also { commit() }
    } catch (failed: Throwable) {
        rollback()
        throw failed
    } finally {
        autoCommit = auto
    }
}

/** Whether this, or a batch's failure behind it, is a duplicate key. */
internal fun SQLException.duplicate(): Boolean = sqlState == DUPLICATE_KEY || nextException?.duplicate() == true
