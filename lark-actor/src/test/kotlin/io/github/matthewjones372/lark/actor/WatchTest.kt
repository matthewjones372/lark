package io.github.matthewjones372.lark.actor

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
}
