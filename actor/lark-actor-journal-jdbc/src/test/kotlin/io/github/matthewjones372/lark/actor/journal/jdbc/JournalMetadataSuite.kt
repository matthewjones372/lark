package io.github.matthewjones372.lark.actor.journal.jdbc

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.junit.Expectations
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.forAll
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeEmpty
import io.github.matthewjones372.lark.logAnnotated
import io.github.matthewjones372.lark.resource

/** Spec 0123: an event is written with what its append carried, and read back with it. */
object JournalMetadataSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private val order = PersistenceId("order", "o-1")
    private val placed = listOf("placed".toByteArray(), "paid".toByteArray())

    private fun carriedBy(journal: JdbcJournal): Expectations {
        logAnnotated("request_id" to "r-1", "quote" to "a \"quoted\" \\ value") { journal.append(order, 0, placed) }
        journal.append(PersistenceId("order", "o-2"), 0, listOf("placed".toByteArray()))

        val expected = mapOf("lark.annotation.request_id" to "r-1", "lark.annotation.quote" to "a \"quoted\" \\ value")
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
        ) { _ ->
            carriedBy(JdbcJournal(Postgres.fresh()))
        }

        test("the same holds for appends committed as a group") { _ ->
            carriedBy(JdbcJournal(Postgres.fresh(), groupCommit = GroupCommit()))
        }
    }
}
