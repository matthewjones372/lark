package io.github.matthewjones372.lark.actor

import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

private val text = object : EventCodec<String> {
    override fun encode(event: String): ByteArray = event.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

private val diaryOf = PersistenceId("diary", "sam")

private sealed interface Diary

private data class Note(val text: String) : Diary

private data class Written(val reply: Reply<List<String>>) : Diary

/** Writes each note straight to its flock's journal, and reads back what is there. */
private fun diary() = behaviour<Diary, Long>(0L) { ctx, last, message ->
    when (message) {
        is Note -> {
            val next = ctx.journal.append(diaryOf, last, listOf(text.encode(message.text))).getOrNull() ?: last
            become(next)
        }

        is Written -> {
            message.reply(ctx.journal.events(diaryOf, text))
            stay()
        }
    }
}

class JournalTest {

    @Test
    fun `a read answers what was appended, in order, from where it is asked to start`() {
        val journal = InMemoryJournal()

        journal.append(diaryOf, 0, listOf(text.encode("one"), text.encode("two"))) shouldBe 2L.right()
        journal.append(diaryOf, 2, listOf(text.encode("three"))) shouldBe 3L.right()

        journal.events(diaryOf, text) shouldContainExactly listOf("one", "two", "three")
        journal.read(diaryOf, from = 3).map { it.sequence to text.decode(it.bytes) } shouldBe listOf(3L to "three")
        journal.read(PersistenceId("diary", "nobody")).shouldBeEmpty()
    }

    @Test
    fun `an append that expects the wrong sequence number is a conflict, and writes nothing`() {
        val journal = InMemoryJournal()
        journal.append(diaryOf, 0, listOf(text.encode("one")))

        journal.append(diaryOf, 0, listOf(text.encode("again"))) shouldBe JournalConflict(diaryOf, 0, 1).left()

        journal.events(diaryOf, text) shouldContainExactly listOf("one")
    }

    @Test
    fun `the journal keeps its own copy of the bytes`() {
        val journal = InMemoryJournal()
        val bytes = text.encode("one")
        journal.append(diaryOf, 0, listOf(bytes))

        bytes[0] = 'x'.code.toByte()
        journal.read(diaryOf).single().bytes[0] = 'y'.code.toByte()

        journal.events(diaryOf, text) shouldContainExactly listOf("one")
    }

    @Test
    fun `a test's actors write to the test's journal, which the test can read`() {
        testActors {
            val diary = spawn("diary", diary())

            diary.send(Note("one"))
            diary.send(Note("two"))

            journal.events(diaryOf, text) shouldContainExactly listOf("one", "two")
        }
    }

    @Test
    fun `on threads, an actor writes to its flock's journal`() {
        val journal = InMemoryJournal()
        val written = flock<Nothing, Any> {
            journal(journal)
            val diary = spawn("diary", diary())
            diary.tell(Note("one"))
            diary.tell(Note("two"))
            diary.ask(1.minutes) { Written(it) }
        }

        written shouldBe listOf("one", "two").right().right()
    }

    @Test
    fun `on threads, a flock with no journal says so`() {
        val failed = shouldThrow<IllegalStateException> { flock<Nothing, Journal> { journal() } }

        failed.message shouldBe "no journal: give the flock one with journal(…)"
    }
}
