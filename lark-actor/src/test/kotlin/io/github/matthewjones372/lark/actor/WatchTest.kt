package io.github.matthewjones372.lark.actor

import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.minutes

private sealed interface Lookout

private data class Watch(val ref: ActorRef<*>) : Lookout

private data class Seen(val reply: Reply<Int>) : Lookout

/** Counts every `Terminated` it hears, and answers `Seen` with the count. */
private fun counted() = behaviour<Lookout, Int>(0) { ctx, heard, message ->
    when (message) {
        is Watch -> {
            ctx.watch(message.ref)
            stay()
        }

        is Seen -> {
            message.reply(heard)
            stay()
        }
    }
}.onSignal { _, heard, signal ->
    when (signal) {
        Signal.Stopping -> stay()
        is Signal.Terminated -> become(heard + 1)
    }
}

/** A ref to something that is not an actor on threads, which says when it has ended once [end] is called. */
private class Faraway : ActorRef<Unit>, Watchable {
    override val address = Address("far@127.0.0.1:1", "/user/far", 1)
    private val notified = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    override fun tell(message: Unit) = Unit

    override fun onTerminated(notify: () -> Unit) {
        notified += notify
    }

    fun end() = notified.forEach { it() }
}

private sealed interface Hand

private data object Leave : Hand

private data object Burst : Hand

private fun worker(stopping: AtomicReference<List<String>>) = behaviour<Hand, Unit>(Unit) { _, _, message ->
    when (message) {
        Leave -> stop()
        Burst -> error("burst")
    }
}.onSignal { _, _, signal ->
    if (signal == Signal.Stopping) stopping.updateAndGet { it + "stopping" }
    stay()
}

class WatchTest {

    @Test
    fun `a watcher hears Terminated once, however often it watches`() {
        testActors {
            val worker = spawn("worker", worker(AtomicReference(emptyList())))
            val lookout = spawn("lookout", counted())

            lookout.send(Watch(worker))
            lookout.send(Watch(worker))
            worker.send(Leave)

            lookout.state shouldBe 1
            lookout.signals shouldContainExactly listOf(Signal.Terminated(worker))
        }
    }

    @Test
    fun `watching an actor that has already stopped hears it at once`() {
        testActors {
            val worker = spawn("worker", worker(AtomicReference(emptyList())))
            val lookout = spawn("lookout", counted())
            worker.send(Leave)

            lookout.send(Watch(worker))

            lookout.state shouldBe 1
        }
    }

    @Test
    fun `Stopping comes once before the actor ends, after a throw as after a stop`() {
        val stopping = AtomicReference<List<String>>(emptyList())
        testActors {
            spawn("quits", worker(stopping)).send(Leave)
            shouldThrow<IllegalStateException> { spawn("bursts", worker(stopping)).send(Burst) }
        }

        stopping.get() shouldBe listOf("stopping", "stopping")
    }

    @Test
    fun `on threads, a watcher hears Terminated once`() {
        val seen = flock<Nothing, Any> {
            val worker = spawn("worker", worker(AtomicReference(emptyList())))
            val lookout = spawn("lookout", counted())
            lookout.tell(Watch(worker))
            lookout.tell(Watch(worker))
            lookout.ask(1.minutes) { Seen(it) }
            worker.tell(Leave)
            watch(worker).await()
            awaitIdle()
            lookout.ask(1.minutes) { Seen(it) }
        }

        seen shouldBe 1.right().right()
    }

    @Test
    fun `on threads, watch from outside answers once the actor has stopped`() {
        val stopping = AtomicReference<List<String>>(emptyList())
        val heard = flock<Nothing, Signal.Terminated> {
            val worker = spawn("worker", worker(stopping))
            worker.tell(Leave)
            watch(worker).await()
        }

        heard.getOrNull()?.ref?.address?.path shouldBe "/user/worker"
        stopping.get() shouldBe listOf("stopping")
    }

    @Test
    fun `on threads, stop from outside ends the actor, and answers once it has`() {
        val stopping = AtomicReference<List<String>>(emptyList())
        val heard = flock<Nothing, Any> {
            val worker = spawn("worker", worker(stopping))
            stop(worker).await().ref shouldBe worker
            worker.ask(1.minutes) { _: Reply<Unit> -> Leave }
        }

        heard shouldBe AskFailure.Stopped.left().right()
        stopping.get() shouldBe listOf("stopping")
    }

    @Test
    fun `on threads, a ref that is not an actor here can be watched, by an actor and by the flock, once it says so`() {
        val far = Faraway()
        val seen = flock<Nothing, Any> {
            val lookout = spawn("lookout", counted())
            lookout.tell(Watch(far))
            lookout.ask(1.minutes) { Seen(it) }
            val watched = watch(far)
            far.end()
            watched.await()
            awaitIdle()
            lookout.ask(1.minutes) { Seen(it) }
        }

        seen shouldBe 1.right().right()
    }

    @Test
    fun `on threads, a letter a transport found reaches the flock's dead-letter handler`() {
        val letters = java.util.concurrent.ConcurrentLinkedQueue<DeadLetter>()
        val far = Faraway()
        flock<Nothing, Unit> {
            onDeadLetter(letters::add)
            deadLetter(DeadLetter(far.address, "hello", DeadLetter.Why.Unreachable))
        }

        letters.toList() shouldContainExactly listOf(DeadLetter(far.address, "hello", DeadLetter.Why.Unreachable))
    }
}
