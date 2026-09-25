package io.github.matthewjones372.lark.actor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Runs this behaviour on the calling thread, for a test: no system, no threads, nothing to wait for. */
fun <M : Any, S> Behaviour<M, S>.test(name: String = "test"): TestActor<M, S> = TestActors().spawn(name, this)

/** Several actors stepped by the test itself, each message delivered in the order it was told. */
fun <A> testActors(block: TestActors.() -> A): A = TestActors().block()

/**
 * The actors of one test. A tell from the test returns once that message, and every message it caused, has been
 * handled; a tell from inside a step joins the queue behind whatever is already on it.
 */
class TestActors internal constructor() {

    private class Delivery<M : Any>(val to: TestActor<M, *>, val message: M) {
        fun deliver() = to.handle(message)
    }

    private val queue = AtomicReference<List<Delivery<*>>>(emptyList())
    private val draining = AtomicBoolean(false)
    private val incarnations = AtomicLong()

    fun <M : Any, S> spawn(name: String, behaviour: Behaviour<M, S>): TestActor<M, S> =
        TestActor(this, behaviour, Address("test", "/user/$name", incarnations.incrementAndGet()))

    /** Returns at once: a tell from the test has already run to idle. Here so a scenario reads the same on threads. */
    fun awaitIdle() = Unit

    internal fun <M : Any> post(to: TestActor<M, *>, message: M) {
        queue.updateAndGet { it + Delivery(to, message) }
        if (draining.compareAndSet(false, true)) {
            try {
                drain()
            } finally {
                draining.set(false)
            }
        }
    }

    private tailrec fun drain() {
        val next = queue.get().firstOrNull() ?: return
        queue.updateAndGet { it.drop(1) }
        next.deliver()
        drain()
    }
}

class TestActor<M : Any, S> internal constructor(
    private val scope: TestActors,
    private val behaviour: Behaviour<M, S>,
    override val address: Address,
) : ActorRef<M> {

    private data class Run<M, S>(val state: S, val stopped: Boolean, val unhandled: List<M>)

    private val run = AtomicReference(Run<M, S>(behaviour.initial, false, emptyList()))
    private val asks = AtomicLong()

    private val ctx = object : Ctx<M> {
        override val self = this@TestActor
    }

    val state: S get() = run.get().state

    val stopped: Boolean get() = run.get().stopped

    val unhandled: List<M> get() = run.get().unhandled

    /** A message to a stopped actor is dropped, as it would be on threads. */
    override fun tell(message: M) = scope.post(this, message)

    /** [tell], for a test that means it: a send to a stopped actor is a mistake in the test and fails. */
    fun send(message: M) {
        check(!stopped) { "$message was sent to ${address.path}, which has stopped" }
        tell(message)
    }

    /** The reply, or why there is none. A message handled without a reply fails here, rather than timing out. */
    fun <A : Any> ask(message: (Reply<A>) -> M): Either<AskFailure, A> {
        if (stopped) return AskFailure.Stopped.left()
        val reply = TestReply<A>(Address(address.node, "/temp/ask-${asks.incrementAndGet()}", 1))
        val asked = message(reply)
        send(asked)
        val answer = reply.answer.get()
        return when {
            answer != null -> answer.right()
            stopped -> AskFailure.Stopped.left()
            else -> error("$asked was handled by ${address.path} and never replied")
        }
    }

    internal fun handle(message: M) {
        if (stopped) return
        var returned = false
        try {
            val next = behaviour.step(ctx, state, message)
            returned = true
            run.updateAndGet { after(it, next, message) }
        } finally {
            // A throw stops the actor, and reaches the test as it was thrown.
            if (!returned) run.updateAndGet { it.copy(stopped = true) }
        }
    }

    private fun after(run: Run<M, S>, next: Next<S>, message: M): Run<M, S> = when (next) {
        Next.Stay -> run
        is Next.Become -> run.copy(state = next.state)
        Next.Stop -> run.copy(stopped = true)
        Next.Unhandled -> run.copy(unhandled = run.unhandled + message)
    }
}

private class TestReply<A : Any>(override val address: Address) : Reply<A> {
    val answer = AtomicReference<A>()

    override fun invoke(answer: A) {
        check(this.answer.compareAndSet(null, answer)) { "a second reply, $answer, after ${this.answer.get()}" }
    }
}
