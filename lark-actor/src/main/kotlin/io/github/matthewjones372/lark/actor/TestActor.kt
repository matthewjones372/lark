package io.github.matthewjones372.lark.actor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Runs this behaviour on the calling thread, for a test: no system, no threads, nothing to wait for. */
fun <M : Any, S> Behaviour<M, S>.test(name: String = "test"): TestActor<M, S> =
    TestActor(this, Address("test", "/user/$name", 1))

/**
 * One actor, stepped by the test itself. [send] and [ask] return once the message, and every message the actor
 * told itself while handling it, has been handled.
 */
class TestActor<M : Any, S> internal constructor(private val behaviour: Behaviour<M, S>, val address: Address) {

    private data class Run<M, S>(val state: S, val stopped: Boolean, val unhandled: List<M>, val pending: List<M>)

    private val run = AtomicReference(Run<M, S>(behaviour.initial, false, emptyList(), emptyList()))
    private val asks = AtomicLong()

    private val self = object : ActorRef<M> {
        override val address = this@TestActor.address

        override fun tell(message: M) {
            run.updateAndGet { it.copy(pending = it.pending + message) }
        }
    }

    private val ctx = object : Ctx<M> {
        override val self = this@TestActor.self
    }

    val state: S get() = run.get().state

    val stopped: Boolean get() = run.get().stopped

    val unhandled: List<M> get() = run.get().unhandled

    fun send(message: M) {
        check(!stopped) { "$message was sent to ${address.path}, which has stopped" }
        self.tell(message)
        drain()
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

    private tailrec fun drain() {
        val current = run.get()
        val message = current.pending.firstOrNull() ?: return
        run.set(current.copy(pending = current.pending.drop(1)))
        handle(current.state, message)
        if (!stopped) drain()
    }

    private fun handle(state: S, message: M) {
        var returned = false
        try {
            val next = behaviour.step(ctx, state, message)
            returned = true
            run.updateAndGet { after(it, next, message) }
        } finally {
            // A throw stops the actor, and reaches the test as it was thrown.
            if (!returned) run.updateAndGet { it.copy(stopped = true, pending = emptyList()) }
        }
    }

    private fun after(run: Run<M, S>, next: Next<S>, message: M): Run<M, S> = when (next) {
        Next.Stay -> run
        is Next.Become -> run.copy(state = next.state)
        Next.Stop -> run.copy(stopped = true, pending = emptyList())
        Next.Unhandled -> run.copy(unhandled = run.unhandled + message)
    }
}

private class TestReply<A : Any>(override val address: Address) : Reply<A> {
    val answer = AtomicReference<A>()

    override fun invoke(answer: A) {
        check(this.answer.compareAndSet(null, answer)) { "a second reply, $answer, after ${this.answer.get()}" }
    }
}
