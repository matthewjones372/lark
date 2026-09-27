package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.FeedContract
import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.JournalContract
import io.github.matthewjones372.lark.actor.OffsetContract
import io.github.matthewjones372.lark.actor.OffsetStore
import io.github.matthewjones372.lark.actor.PruneContract
import io.github.matthewjones372.lark.actor.SnapshotContract
import io.github.matthewjones372.lark.actor.SnapshotStore

// Each contract the H2 classes run, on a real Postgres (spec 0078).

class PostgresJournalTest : JournalContract() {
    override fun journal(): Journal = JdbcJournal(Postgres.fresh())
}

class PostgresFeedTest : FeedContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(Postgres.fresh())
}

class PostgresPruneTest : PruneContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(Postgres.fresh())
}

class PostgresSnapshotsTest : SnapshotContract() {
    override fun store(): SnapshotStore = JdbcSnapshots(Postgres.fresh())
}

class PostgresOffsetsTest : OffsetContract() {
    override fun store(): OffsetStore = JdbcOffsets(Postgres.fresh())
}
