package io.github.matthewjones372.lark.actor.projection

import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.actor.Confirmed
import io.github.matthewjones372.lark.actor.Delivered
import io.github.matthewjones372.lark.actor.Delivery
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.InMemoryJournal
import io.github.matthewjones372.lark.actor.InMemoryOffsets
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.delivered
import io.github.matthewjones372.lark.actor.persistent
import io.github.matthewjones372.lark.actor.stay
import io.github.matthewjones372.lark.actor.testActors
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.logAnnotated
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

/** An order placed reliably. */
private data class Place(val n: Int, override val delivery: Delivery) : Delivered

/** Places orders sent reliably, so each append carries its delivery's mark after the event. */
private fun placing(n: Int) = delivered(
    persistent<Place, String, Unit>(
        id = PersistenceId("order", "o-$n"),
        empty = Unit,
        codec = text,
        command = { _, _, place -> persist("placed ${place.n}") },
        event = { _, _ -> },
    ),
)

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

    @Test
    fun `the marks of reliable deliveries are skipped, and the offset passes them`() {
        testActors(journal = journal) {
            val confirms = spawn("confirms", behaviour<Confirmed, Unit>(Unit) { _, _, _ -> stay() })
            for (n in 1..3) spawn("o-$n", placing(n)).send(Place(n, Delivery("checkout", "o-$n", 1, confirms)))
        }

        totals().take(3).runProjecting().run(Forks()).settled() shouldBe Exit.Done(3L)

        handled.toList() shouldContainExactly (1..3).map { "o-$it: placed $it" }
        offsets.load("totals") shouldBe journal.after("order", 0, 100)[4].offset
    }

    @Test
    fun `an event read as two is followed as two, and a run stopped between them reads it again from the first`() {
        val split = object : EventCodec<String> {
            override fun encode(event: String): ByteArray = event.toByteArray()

            override fun decode(bytes: ByteArray): String = String(bytes)

            override fun decodeAll(bytes: ByteArray): List<String> = String(bytes).split("+")
        }
        journal.append(PersistenceId("order", "o-1"), 0, listOf(split.encode("placed+paid")))
        journal.append(PersistenceId("order", "o-2"), 0, listOf(split.encode("placed")))
        fun follow() = Projection.follow(journal, kind = "order", codec = split, offsets = offsets, name = "totals")
            .mapFollowed { event -> event.value.also { handled += "${event.id.id}: $it" } }

        follow().take(1).runProjecting().run(Forks()).settled() shouldBe Exit.Done(1L)
        follow().take(3).runProjecting().run(Forks()).settled() shouldBe Exit.Done(3L)

        handled.toList() shouldContainExactly listOf("o-1: placed", "o-1: placed", "o-1: paid", "o-2: placed")
    }

    @Test
    fun `a followed event carries what its append carried, for a publisher to send on`() {
        logAnnotated("request_id" to "r-4") { journal.place(1) }
        journal.place(2)
        val seen = ConcurrentLinkedQueue<Map<String, String>>()

        Projection.follow(journal, kind = "order", codec = text, offsets = offsets, name = "carried")
            .mapFollowed { event -> event.value.also { seen += event.metadata } }
            .take(2).runProjecting().run(Forks()).settled() shouldBe Exit.Done(2L)

        seen.toList() shouldBe listOf(mapOf("lark.annotation.request_id" to "r-4"), emptyMap())
    }
}
