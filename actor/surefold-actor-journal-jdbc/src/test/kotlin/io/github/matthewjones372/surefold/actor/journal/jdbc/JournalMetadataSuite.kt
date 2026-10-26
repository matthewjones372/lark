package io.github.matthewjones372.surefold.actor.journal.jdbc

import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.junit.Expectations
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.forAll
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldBeEmpty
import io.github.matthewjones372.surefold.logAnnotated

/** Spec 0123: an event is written with what its append carried, and read back with it. */
object JournalMetadataSuite : Suite() {
    private val order = PersistenceId("order", "o-1")
    private val placed = listOf("placed".toByteArray(), "paid".toByteArray())

    private fun carriedBy(journal: JdbcJournal): Expectations {
        logAnnotated("request_id" to "r-1", "quote" to "a \"quoted\" \\ value") { journal.append(order, 0, placed) }
        journal.append(PersistenceId("order", "o-2"), 0, listOf("placed".toByteArray()))

        val expected = mapOf(
            "surefold.annotation.request_id" to "r-1",
            "surefold.annotation.quote" to "a \"quoted\" \\ value",
        )
        val read = journal.read(order).forAll { it.metadata shouldBe expected } and
            journal.read(PersistenceId("order", "o-2")).single().metadata.entries.shouldBeEmpty()

        val feed = journal.after("order", 0, 10)
        return read and
            feed.filter { it.id == order }.forAll { it.metadata shouldBe expected } and
            feed.single { it.id.id == "o-2" }.metadata.entries.shouldBeEmpty() and
            (feed.size shouldBe 3)
    }

    init {
        test(
            "an append made inside logAnnotated is read back, and fed, with its annotations, and one outside with none",
        ) {
            carriedBy(JdbcJournal(Postgres.fresh()))
        }

        test("the same holds for appends committed as a group") {
            carriedBy(JdbcJournal(Postgres.fresh(), groupCommit = GroupCommit()))
        }
    }
}
