package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.FeedContract
import io.github.matthewjones372.lark.actor.PruneContract

class JdbcFeedTest : FeedContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(Postgres.fresh())
}

class JdbcPruneTest : PruneContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(Postgres.fresh())
}
