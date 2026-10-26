package io.github.matthewjones372.surefold.actor.journal.jdbc

import io.github.matthewjones372.surefold.actor.FeedContract
import io.github.matthewjones372.surefold.actor.PruneContract

class JdbcFeedTest : FeedContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(Postgres.fresh())
}

class JdbcPruneTest : PruneContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(Postgres.fresh())
}
