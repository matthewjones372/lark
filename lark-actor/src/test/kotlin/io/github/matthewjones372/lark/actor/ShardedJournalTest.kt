package io.github.matthewjones372.lark.actor

import arrow.core.Either
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Spec 0088: a journal split across databases by id. */
class ShardedJournalTest {

    private fun two() = listOf("db-a" to InMemoryJournal(), "db-b" to InMemoryJournal())

    @Test
    fun `murmur3 answers the reference values, so a slice never moves`() {
        mapOf(
            "" to 0,
            "a" to 1009084850,
            "ab" to -1681926305,
            "abc" to -1277324294,
            "abcd" to 1139631978,
            "hello" to 613153351,
            "account|acc-1" to 1232564952,
            "transfer|7f3c0e2a-1b4d-4c55-9e0e-1f2a3b4c5d6e" to -1796319154,
            "The quick brown fox jumps over the lazy dog" to 776992547,
        ).forEach { (text, hash) -> withClue(text) { murmur3(text.toByteArray()) shouldBe hash } }
    }

    @Test
    fun `an id's events are written to one database, and read back from it`() {
        val databases = two()
        val journal = ShardedJournal(databases)
        val id = PersistenceId("account", "acc-1")
        journal.append(id, 0, listOf(byteArrayOf(1), byteArrayOf(2))) shouldBe Either.Right(2L)

        val (home, other) = databases.partition { it.first == journal.database(id) }
        home.single().second.read(id).map { it.sequence } shouldContainExactly listOf(1L, 2L)
        other.single().second.read(id).shouldBeEmpty()
        journal.read(id, from = 2).map { it.sequence } shouldContainExactly listOf(2L)
    }

    @Test
    fun `the same names in the same order route an id to the same database every time`() {
        val ids = (1..1_000).map { PersistenceId("account", "acc-$it") }
        val first = ShardedJournal(two())
        val second = ShardedJournal(two())
        ids.map(first::database) shouldContainExactly ids.map(second::database)
    }

    @Test
    fun `a conflict is still a conflict`() {
        val journal = ShardedJournal(two())
        val id = PersistenceId("account", "acc-1")
        journal.append(id, 0, listOf(byteArrayOf(1)))
        journal.append(id, 0, listOf(byteArrayOf(2))) shouldBe Either.Left(JournalConflict(id, 0, 1))
    }

    @Test
    fun `ten thousand ids split within five percent of even`() {
        listOf(2, 3, 4).forEach { n ->
            val journal = ShardedJournal((1..n).map { "db-$it" to InMemoryJournal() })
            val counts = (1..10_000).groupingBy { journal.database(PersistenceId("account", "acc-$it")) }.eachCount()
            val even = 10_000 / n
            withClue("$n databases: $counts") {
                counts.size shouldBe n
                counts.values.forEach { it shouldBeInRange (even * 95 / 100)..(even * 105 / 100) }
            }
        }
    }

    @Test
    fun `a snapshot sits in the database its id's events do`() {
        val stores = listOf("db-a" to InMemorySnapshots(), "db-b" to InMemorySnapshots())
        val journal = ShardedJournal(two())
        val snapshots = ShardedSnapshots(stores)
        (1..50).map { PersistenceId("account", "acc-$it") }.forEach { id ->
            snapshots.save(id, 3, byteArrayOf(3))
            snapshots.latest(id)?.sequence shouldBe 3
            stores.single { it.first == journal.database(id) }.second.latest(id)?.sequence shouldBe 3
        }
    }

    @Test
    fun `pruning deletes in the id's own database`() {
        val journal = ShardedJournal(two())
        val id = PersistenceId("account", "acc-1")
        journal.append(id, 0, (1..5).map { byteArrayOf(it.toByte()) })
        journal.deleteTo(id, 3)
        journal.read(id).map { it.sequence } shouldContainExactly listOf(4L, 5L)
    }

    @Test
    fun `databases named twice, or none at all, are refused`() {
        shouldThrow<IllegalArgumentException> { ShardedJournal(emptyList()) }
        shouldThrow<IllegalArgumentException> {
            ShardedJournal(listOf("a" to InMemoryJournal(), "a" to InMemoryJournal()))
        }
    }
}
