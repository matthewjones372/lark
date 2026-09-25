package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.flock
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** What a scenario needs from a runtime, so the same scenario runs on the threads and on the test scope. */
private interface Actors {
    fun <M : Any, S> spawn(name: String, behaviour: Behaviour<M, S>): ActorRef<M>

    fun awaitIdle()
}

private interface Runtime {
    fun <A> run(scenario: Actors.() -> A): A
}

private val threads = object : Runtime {
    override fun <A> run(scenario: Actors.() -> A): A = flock<Nothing, A> {
        val flock = this
        object : Actors {
            override fun <M : Any, S> spawn(name: String, behaviour: Behaviour<M, S>) = flock.spawn(name, behaviour)

            override fun awaitIdle() = flock.awaitIdle()
        }.scenario()
    }.getOrNull()!!

    override fun toString() = "threads"
}

private val testScope = object : Runtime {
    override fun <A> run(scenario: Actors.() -> A): A = testActors {
        val scope = this
        object : Actors {
            override fun <M : Any, S> spawn(name: String, behaviour: Behaviour<M, S>) = scope.spawn(name, behaviour)

            override fun awaitIdle() = scope.awaitIdle()
        }.scenario()
    }

    override fun toString() = "test scope"
}

private data class Ball(val left: Int, val back: ActorRef<Ball>)

/** Counts every ball it sees, then returns it with one fewer bounce, until none are left. */
private fun paddle(seen: AtomicInteger): Behaviour<Ball, Unit> = behaviour(Unit) { ctx, _, ball ->
    seen.incrementAndGet()
    if (ball.left > 0) ball.back.tell(Ball(ball.left - 1, ctx.self))
    stay()
}

private data class Hop(val path: List<String>)

private fun relay(name: String, next: ActorRef<Hop>?, arrived: AtomicReference<List<String>>) =
    behaviour<Hop, Unit>(Unit) { _, _, hop ->
        val path = hop.path + name
        if (next == null) arrived.set(path) else next.tell(Hop(path))
        stay()
    }

private data object Go

private fun sender(to: ActorRef<Int>, count: Int) = behaviour<Go, Unit>(Unit) { _, _, _ ->
    (1..count).forEach { to.tell(it) }
    stay()
}

private fun sink(total: AtomicInteger) = behaviour<Int, Unit>(Unit) { _, _, n ->
    total.addAndGet(n)
    stay()
}

class ParityTest {

    private val runtimes = listOf(threads, testScope)

    private fun <A> onEvery(expected: A, scenario: Actors.() -> A) = runtimes.forEach { runtime ->
        withClue("on $runtime") { runtime.run(scenario) shouldBe expected }
    }

    @Test
    fun `a ball bounced between two actors is counted every time before awaitIdle returns`() =
        onEvery(10_001) {
            val seen = AtomicInteger()
            val left = spawn("left", paddle(seen))
            val right = spawn("right", paddle(seen))
            left.tell(Ball(10_000, right))
            awaitIdle()
            seen.get()
        }

    @Test
    fun `a message relayed through three actors arrives having visited each in order`() =
        onEvery(listOf("a", "b", "c")) {
            val arrived = AtomicReference<List<String>>(emptyList())
            val c = spawn("c", relay("c", null, arrived))
            val b = spawn("b", relay("b", c, arrived))
            val a = spawn("a", relay("a", b, arrived))
            a.tell(Hop(emptyList()))
            awaitIdle()
            arrived.get()
        }

    @Test
    fun `ten senders into one sink are all summed before awaitIdle returns`() =
        onEvery(10 * (1..90).sum()) {
            val total = AtomicInteger()
            val sink = spawn("sink", sink(total))
            (1..10).map { spawn("sender-$it", sender(sink, 90)) }.forEach { it.tell(Go) }
            awaitIdle()
            total.get()
        }
}
