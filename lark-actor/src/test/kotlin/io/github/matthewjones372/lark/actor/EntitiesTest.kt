package io.github.matthewjones372.lark.actor

import arrow.core.right
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

private sealed interface Kennel

private data class Feed(val grams: Int) : Kennel

private data class Weight(val reply: Reply<Pair<String, Int>>) : Kennel

/** Tells the pup it will be called back as it stops: a message that arrives while its entity is stopping. */
private data object Recall : Kennel

/** Keeps the pup's step busy until [gate] opens, whatever interrupts it: says when it began, and when interrupted. */
private data class Busy(val began: CountDownLatch, val gate: CountDownLatch, val interrupted: CountDownLatch) : Kennel

/**
 * One pup per id, fed and weighed. As it stops it writes down that it did, and, when asked to, sends itself one more
 * message through the entities, which reaches the manager while the pup is stopping.
 */
private fun pup(
    id: String,
    log: ConcurrentLinkedQueue<String>,
    kennel: AtomicReference<ActorRef<Entities<Kennel>>> = AtomicReference(),
) = behaviour<Kennel, Pair<Int, Boolean>>(0 to false) { _, (grams, recall), message ->
    when (message) {
        is Feed -> become(grams + message.grams to recall)

        is Weight -> {
            message.reply(id to grams)
            stay()
        }

        Recall -> become(grams to true)

        is Busy -> {
            message.began.countDown()
            while (message.gate.count > 0) {
                try {
                    message.gate.await()
                } catch (interrupt: InterruptedException) {
                    message.interrupted.countDown()
                }
            }
            stay()
        }
    }
}.onStart { log += "$id started" }.onSignal { _, (_, recall), signal ->
    if (signal == Signal.Stopping) {
        log += "$id stopped"
        if (recall) kennel.get().entity(id).tell(Feed(1))
    }
    stay()
}

class EntitiesTest {

    private val log = ConcurrentLinkedQueue<String>()

    @Test
    fun `one id is one actor, and two ids are two`() {
        val kennel = entities(passivateAfter = 10.minutes) { id -> pup(id, log) }.test("kennel")
        val rex = kennel.entity("rex")
        val bo = kennel.entity("bo")

        rex.tell(Feed(50))
        rex.tell(Feed(25))
        bo.tell(Feed(10))

        rex.ask(1.minutes) { Weight(it) } shouldBe ("rex" to 75).right()
        bo.ask(1.minutes) { Weight(it) } shouldBe ("bo" to 10).right()
        kennel.children.size shouldBe 2
    }

    @Test
    fun `an idle entity is stopped, and comes back on its next message`() {
        val kennel = entities(passivateAfter = 10.minutes) { id -> pup(id, log) }.test("kennel")
        val rex = kennel.entity("rex")
        rex.tell(Feed(50))

        kennel.advance(9.minutes)
        rex.tell(Feed(5))
        kennel.advance(9.minutes)
        log.filter { it.endsWith("stopped") } shouldContainExactly emptyList()

        kennel.advance(1.minutes)
        log.filter { it.endsWith("stopped") } shouldContainExactly listOf("rex stopped")

        rex.ask(1.minutes) { Weight(it) } shouldBe ("rex" to 0).right()
    }

    @Test
    fun `a message for an entity that arrives while it stops is kept, and starts it again`() {
        val kennel = AtomicReference<ActorRef<Entities<Kennel>>>()
        val running = entities(passivateAfter = 10.minutes) { id -> pup(id, log, kennel) }.test("kennel")
        kennel.set(running)
        val rex = running.entity("rex")
        rex.tell(Recall)

        running.advance(10.minutes)

        log.filter { it.endsWith("stopped") } shouldContainExactly listOf("rex stopped")
        rex.ask(1.minutes) { Weight(it) } shouldBe ("rex" to 1).right()
    }

    @Test
    fun `an entity ref is the same ref however often it is asked for`() {
        val kennel = entities(passivateAfter = 10.minutes) { id -> pup(id, log) }.test("kennel")

        kennel.entity("rex") shouldBe kennel.entity("rex")
        kennel.entity("rex").address.path shouldBe "/user/kennel/rex"
    }

    @Test
    fun `on threads, entities come and go on the flock's clock, and a message sent as one stops is kept`() {
        val moving = TestClock()
        val kennel = AtomicReference<ActorRef<Entities<Kennel>>>()
        val weights = clock.locally(moving) {
            flock<Nothing, Any> {
                val running =
                    spawn("kennel", entities(passivateAfter = 10.minutes) { id -> pup(id, log, kennel) })
                kennel.set(running)
                running.entity("rex").tell(Feed(50))
                running.entity("bo").tell(Recall)
                awaitIdle()

                moving.adjust(10.minutes)
                awaitIdle()
                val passivated = log.filter { it.endsWith("stopped") }.sorted()
                val rex = running.entity("rex").ask(1.minutes) { Weight(it) }
                val bo = running.entity("bo").ask(1.minutes) { Weight(it) }
                Triple(passivated, rex, bo)
            }
        }

        weights shouldBe Triple(listOf("bo stopped", "rex stopped"), ("rex" to 0).right(), ("bo" to 1).right()).right()
    }

    @Test
    fun `on threads, an entity that is still stopping is not started again until it has stopped`() {
        val moving = TestClock()
        val began = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        // The manager makes an entity inside its own step, so "made" is written the moment it decides to start one.
        val made = entities(passivateAfter = 10.minutes) { id -> pup(id, log).also { log += "$id made" } }
        val seen = clock.locally(moving) {
            flock<Nothing, Any> {
                val running = spawn("kennel", made)
                val rex = running.entity("rex")
                rex.tell(Busy(began, gate, interrupted))
                began.await()
                // Once passivation has interrupted rex's busy step, rex is stopping. A message for it now must wait,
                // and bo's answer says the manager has already decided what to do with it.
                val helper = Thread.ofVirtual().start {
                    interrupted.await()
                    rex.tell(Feed(1))
                    running.entity("bo").ask(1.minutes) { Weight(it) }
                    gate.countDown()
                }
                moving.adjust(10.minutes)
                helper.join()
                awaitIdle()
                log.filter { it.startsWith("rex") && !it.endsWith("started") } to rex.ask(1.minutes) { Weight(it) }
            }
        }

        seen shouldBe (listOf("rex made", "rex stopped", "rex made") to ("rex" to 1).right()).right()
    }

    /** An entity whose first message holds its step until [open], then keeps every number it is told, in order. */
    private fun held(open: CountDownLatch, heard: MutableList<Int>) = behaviour<Int, Unit>(Unit) { _, _, n ->
        if (n == 0) open.await() else heard += n
        stay()
    }

    @Test
    fun `a burst to a busy entity is kept in order, and its manager goes on`() {
        val heard = ConcurrentHashMap<String, MutableList<Int>>()
        fun heardBy(id: String) = heard.computeIfAbsent(id) { Collections.synchronizedList(mutableListOf()) }
        val open = CountDownLatch(1)
        val letters = ConcurrentLinkedQueue<DeadLetter>()

        flock<Nothing, Unit> {
            onDeadLetter(letters::add)
            val kennel = spawn("kennel", entities(passivateAfter = 10.minutes) { id -> held(open, heardBy(id)) })
            val rex = kennel.entity("rex")
            rex.tell(0)
            (1..5_000).forEach(rex::tell)
            open.countDown()
            kennel.entity("bo").tell(7)
            awaitIdle()
        }

        heardBy("rex").toList() shouldContainExactly (1..5_000).toList()
        heardBy("bo").toList() shouldContainExactly listOf(7)
        letters.toList() shouldBe emptyList()
    }

    @Test
    fun `past what a manager may keep, a message to a busy entity is a dead letter for being full`() {
        val heard = Collections.synchronizedList(mutableListOf<Int>())
        val open = CountDownLatch(1)
        val letters = ConcurrentLinkedQueue<DeadLetter>()

        flock<Nothing, Unit> {
            onDeadLetter(letters::add)
            val rex = spawn("kennel", entities(passivateAfter = 10.minutes) { _ -> held(open, heard) }).entity("rex")
            rex.tell(0)
            (1..KEEP_AT_MOST + 2_000).forEach(rex::tell)
            open.countDown()
            awaitIdle()
        }

        val full = letters.filter { it.why == DeadLetter.Why.Full }
        full.size shouldBeGreaterThan 0
        full.first().recipient.path shouldBe "/user/kennel/rex"
        (heard.size + full.size) shouldBe KEEP_AT_MOST + 2_000
        heard.toList() shouldBe heard.sorted()
    }
}
