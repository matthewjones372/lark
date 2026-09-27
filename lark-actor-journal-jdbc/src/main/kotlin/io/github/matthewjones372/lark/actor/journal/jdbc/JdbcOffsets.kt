package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.OffsetStore
import java.sql.SQLException
import javax.sql.DataSource

/**
 * An [OffsetStore] in one `lark_offset` table, reached through [dataSource], one row per read model (spec 0075). Its
 * DDL ships beside the journal's. A save replaces the row, or inserts it for a name saved for the first time.
 */
class JdbcOffsets(private val dataSource: DataSource) : OffsetStore {

    override fun load(name: String): Long? = dataSource.connection.use { connection ->
        connection.statement("select last_ordering from lark_offset where name = ?", name) {
            it.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }
    }

    override fun save(name: String, offset: Long) = dataSource.connection.use { connection ->
        val replace = "update lark_offset set last_ordering = ? where name = ?"
        if (connection.statement(replace, offset, name) { it.executeUpdate() } > 0) return@use
        try {
            connection.statement("insert into lark_offset (name, last_ordering) values (?, ?)", name, offset) {
                it.executeUpdate()
            }
        } catch (taken: SQLException) {
            if (taken.sqlState != DUPLICATE_KEY) throw taken
            // Another save inserted first; this one replaces it.
            connection.statement(replace, offset, name) { it.executeUpdate() }
        }
    }
}
