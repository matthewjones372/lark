package io.github.matthewjones372.surefold.actor.journal.jdbc

import io.github.matthewjones372.surefold.actor.Journal
import io.github.matthewjones372.surefold.actor.JournalContract

class GroupCommitJournalTest : JournalContract() {
    override fun journal(): Journal = JdbcJournal(Postgres.fresh(), groupCommit = GroupCommit())
}
