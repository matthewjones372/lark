package io.github.matthewjones372.lark.actor

import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
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
