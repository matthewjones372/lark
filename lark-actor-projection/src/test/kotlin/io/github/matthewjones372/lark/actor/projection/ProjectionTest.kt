package io.github.matthewjones372.lark.actor.projection

import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.InMemoryJournal
import io.github.matthewjones372.lark.actor.InMemoryOffsets
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.take
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private val text = object : EventCodec<String> {
    override fun encode(event: String): ByteArray = event.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

private fun InMemoryJournal.place(n: Int) {
    val id = PersistenceId("order", "o-$n")
    append(id, 0, listOf(text.encode("placed $n")))
}

private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().get(1, TimeUnit.MINUTES)

class ProjectionTest {

    private val journal = InMemoryJournal()
    private val offsets = InMemoryOffsets()
    private val handled = ConcurrentLinkedQueue<String>()

    private fun totals(): Stream<Nothing, Followed<String>> =
        Projection.follow(journal, kind = "order", codec = text, offsets = offsets, name = "totals")
            .mapFollowed { event -> event.value.also { handled += "${event.id.id}: $it" } }

    @Test
    fun `a projection stopped after 30 of 50 events starts again at the 31st`() {
        (1..50).forEach(journal::place)

        totals().take(30).runProjecting().run(Forks()).settled() shouldBe Exit.Done(30L)
        handled.last() shouldBe "o-30: placed 30"

        handled.clear()
        val caughtUp = CountDownLatch(20)
        val again = totals()
            .mapFollowed { event -> event.value.also { caughtUp.countDown() } }
            .runProjecting()
            .start(Forks())
        caughtUp.await(1, TimeUnit.MINUTES) shouldBe true
        again.stop()

        again.exit.settled() shouldBe Exit.Done(20L)
        handled.toList() shouldContainExactly (31..50).map { "o-$it: placed $it" }
        offsets.load("totals") shouldBe journal.after("order", 0, 100).last().offset
    }

    @Test
    fun `an event appended while a projection has caught up reaches it on the next poll`() {
        val moving = TestClock()
        val reached = CountDownLatch(1)
        clock.locally(moving) {
            journal.place(1)
            val running = Projection.follow(journal, "order", text, offsets, "totals", every = 5.seconds)
                .mapFollowed { event -> event.value.also { if (event.id.id == "o-2") reached.countDown() } }
                .runProjecting()
                .start(Forks())

            // Caught up after the first event, the projection sleeps until the next poll; the second is appended
            // meanwhile and is read when the clock reaches it.
            moving.adjustWhenBlocked(0.seconds)
            journal.place(2)
            moving.adjustWhenBlocked(5.seconds)

            reached.await(1, TimeUnit.MINUTES) shouldBe true
            running.stop()
            running.exit.settled() shouldBe Exit.Done(2L)
        }
    }
}
