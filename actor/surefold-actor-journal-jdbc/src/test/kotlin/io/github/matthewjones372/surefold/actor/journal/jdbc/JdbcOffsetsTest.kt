package io.github.matthewjones372.surefold.actor.journal.jdbc

import io.github.matthewjones372.surefold.actor.OffsetContract
import io.github.matthewjones372.surefold.actor.OffsetStore

class JdbcOffsetsTest : OffsetContract() {
    override fun store(): OffsetStore = JdbcOffsets(Postgres.fresh())
}
