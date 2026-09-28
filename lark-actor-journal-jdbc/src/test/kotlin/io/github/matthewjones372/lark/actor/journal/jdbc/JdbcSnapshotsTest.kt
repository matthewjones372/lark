package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.SnapshotContract
import io.github.matthewjones372.lark.actor.SnapshotStore

class JdbcSnapshotsTest : SnapshotContract() {
    override fun store(): SnapshotStore = JdbcSnapshots(
        database().migrated("h2"),
    )
}
