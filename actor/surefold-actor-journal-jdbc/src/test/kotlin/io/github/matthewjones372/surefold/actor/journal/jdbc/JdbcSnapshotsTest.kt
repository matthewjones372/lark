package io.github.matthewjones372.surefold.actor.journal.jdbc

import io.github.matthewjones372.surefold.actor.SnapshotContract
import io.github.matthewjones372.surefold.actor.SnapshotStore

class JdbcSnapshotsTest : SnapshotContract() {
    override fun store(): SnapshotStore = JdbcSnapshots(
        Postgres.fresh(),
    )
}
