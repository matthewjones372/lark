package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.logAnnotated
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Spec 0123: an event is written with what its append carried, and read back with it. */
class JournalMetadataTest {

    private val order = PersistenceId("order", "o-1")
    private val placed = listOf("placed".toByteArray(), "paid".toByteArray())

    private fun carriedBy(journal: JdbcJournal) {
        logAnnotated("request_id" to "r-1", "quote" to "a \"quoted\" \\ value") { journal.append(order, 0, placed) }
        journal.append(PersistenceId("order", "o-2"), 0, listOf("placed".toByteArray()))

        val expected = mapOf("lark.annotation.request_id" to "r-1", "lark.annotation.quote" to "a \"quoted\" \\ value")
        journal.read(order).forEach { it.metadata shouldContainExactly expected }
        journal.read(PersistenceId("order", "o-2")).single().metadata.entries.shouldBeEmpty()

        val feed = journal.after("order", 0, 10)
        feed.filter { it.id == order }.forEach { it.metadata shouldContainExactly expected }
        feed.single { it.id.id == "o-2" }.metadata.entries.shouldBeEmpty()
        feed.size shouldBe 3
    }

    @Test
    fun `an append made inside logAnnotated is read back, and fed, with its annotations, and one outside with none`() =
        carriedBy(JdbcJournal(Postgres.fresh()))

    @Test
    fun `the same holds for appends committed as a group`() =
        carriedBy(JdbcJournal(Postgres.fresh(), groupCommit = GroupCommit()))
}
