package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.Journal
import io.github.matthewjones372.lark.actor.JournalContract

class GroupCommitJournalTest : JournalContract() {
    override fun journal(): Journal = JdbcJournal(Postgres.fresh(), groupCommit = GroupCommit())
}
