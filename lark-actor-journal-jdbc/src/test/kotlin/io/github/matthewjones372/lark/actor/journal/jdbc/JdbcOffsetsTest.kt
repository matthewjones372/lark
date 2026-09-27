package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.OffsetContract
import io.github.matthewjones372.lark.actor.OffsetStore

class JdbcOffsetsTest : OffsetContract() {
    override fun store(): OffsetStore = JdbcOffsets(database().migrated("h2"))
}
