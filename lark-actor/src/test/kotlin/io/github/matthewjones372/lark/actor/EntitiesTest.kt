package io.github.matthewjones372.lark.actor

import arrow.core.right
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
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

    @Test
    fun `the running count falls as entities passivate, stop themselves, and stop with their manager`() {
        val moving = TestClock()
        val running = AtomicInteger()
        val counts = mutableListOf<Int>()
        val leaving = behaviour<String, Unit>(Unit) { _, _, message -> if (message == "leave") stop() else stay() }

        clock.locally(moving) {
            flock<Nothing, Unit> {
                val manager = spawn("kennel", entities(10.minutes, onRunning = { running.addAndGet(it) }) { leaving })
                fun counted() {
                    awaitIdle()
                    counts += running.get()
                }
                listOf("rex", "bo", "fido").forEach { manager.entity(it).tell("hello") }
                counted()
                manager.entity("bo").tell("leave")
                counted()
                moving.adjust(5.minutes)
                manager.entity("fido").tell("hello")
                moving.adjust(5.minutes)
                counted()
                stop(manager).await()
                counted()
            }
        }

        // Three run; bo stops itself; rex passivates while fido, told again, runs on; then fido stops with the manager.
        counts shouldContainExactly listOf(3, 2, 1, 0)
    }
}
