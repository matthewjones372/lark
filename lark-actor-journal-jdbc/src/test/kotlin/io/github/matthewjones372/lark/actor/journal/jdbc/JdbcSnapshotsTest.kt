package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.SnapshotContract
import io.github.matthewjones372.lark.actor.SnapshotStore
import org.h2.jdbcx.JdbcDataSource
import java.util.UUID

class JdbcSnapshotsTest : SnapshotContract() {
    override fun store(): SnapshotStore = JdbcSnapshots(
        JdbcDataSource().apply {
            setURL("jdbc:h2:mem:snapshots-${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
            val ddl = checkNotNull(JdbcSnapshots::class.java.getResource("/lark/journal/jdbc/h2.sql")).readText()
            connection.use { connection -> connection.createStatement().use { statement -> statement.execute(ddl) } }
        },
    )
}
