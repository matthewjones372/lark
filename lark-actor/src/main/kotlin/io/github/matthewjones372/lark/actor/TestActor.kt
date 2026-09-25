package io.github.matthewjones372.lark.actor

import arrow.core.Either
import arrow.core.left
import arrow.core.nonFatalOrThrow
import arrow.core.right
import io.github.matthewjones372.lark.Clock
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.ScheduleStep
import io.github.matthewjones372.lark.fixedClock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

/** Runs this behaviour on the calling thread, for a test: no system, no threads, nothing to wait for. */
fun <M : Any, S, E> Behaviour<M, S, E>.test(
    name: String = "test",
    restart: Schedule<Failure<E>, *>? = null,
): TestActor<M, S, E> = TestActors().spawn(name, this, restart)

/**
 * Several actors stepped by the test itself, each message delivered in the order it was told. A restart waits on
 * [clock], which by default does not wait: each delay is recorded in [TestActor.delays] instead.
 */
fun <A> testActors(clock: Clock = fixedClock(), block: TestActors.() -> A): A = TestActors(clock).block()

/**
 * The actors of one test. A tell from the test returns once that message, and every message it caused, has been
 * handled; a tell from inside a step joins the queue behind whatever is already on it.
 */
class TestActors internal constructor(private val clock: Clock = fixedClock()) {

    private class Delivery<M : Any>(val to: TestActor<M, *, *>, val message: M) {
        fun deliver() = to.handle(message)
    }

    private val queue = AtomicReference<List<Delivery<*>>>(emptyList())
    private val draining = AtomicBoolean(false)
    private val incarnations = AtomicLong()

    fun <M : Any, S, E> spawn(
        name: String,
        behaviour: Behaviour<M, S, E>,
        restart: Schedule<Failure<E>, *>? = null,
    ): TestActor<M, S, E> =
        TestActor(this, behaviour, Address("test", "/user/$name", incarnations.incrementAndGet()), restart, clock)

    /** Returns at once: a tell from the test has already run to idle. Here so a scenario reads the same on threads. */
    fun awaitIdle() = Unit

    internal fun <M : Any> post(to: TestActor<M, *, *>, message: M) {
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

class TestActor<M : Any, S, E> internal constructor(
    private val scope: TestActors,
    private val behaviour: Behaviour<M, S, E>,
    override val address: Address,
    restart: Schedule<Failure<E>, *>?,
    private val clock: Clock,
) : ActorRef<M> {

    private data class Run<M, S, E>(
        val state: S,
        val stopped: Boolean,
        val unhandled: List<M>,
        val failure: Failure<E>?,
        val supervision: ScheduleStep<Failure<E>, *>?,
        val delays: List<Duration>,
    )

    private val run =
        AtomicReference(Run<M, S, E>(behaviour.initial, false, emptyList(), null, restart?.step, emptyList()))
    private val boundary = StepRaise<E>()
    private val asks = AtomicLong()

    private val ctx = object : Ctx<M> {
        override val self = this@TestActor
    }

    val state: S get() = run.get().state

    val stopped: Boolean get() = run.get().stopped

    val unhandled: List<M> get() = run.get().unhandled

    /** Why the actor stopped, when it was a raise or a throw rather than `stop()`. */
    val failure: Failure<E>? get() = run.get().failure

    val restarts: Int get() = run.get().delays.size

    /** The delay each restart waited, in order. */
    val delays: List<Duration> get() = run.get().delays

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

    // A throw is the actor's failure, handed to its schedule like a raise; with no schedule it reaches the test.
    @Suppress("TooGenericExceptionCaught")
    internal fun handle(message: M) {
        if (stopped) return
        val next = try {
            boundary.guarded({ behaviour.step(this, ctx, state, message) }) { error -> failed(Failure.Raised(error)) }
        } catch (thrown: Throwable) {
            failed(Failure.Thrown(thrown.nonFatalOrThrow()))
            if (run.get().supervision == null) throw thrown
            if (!stopped) return
            Next.Stop
        }
        run.updateAndGet { after(it, next, message) }
    }

    /** Restarts from the initial state after the schedule's delay, or stops when there is no schedule or it is done. */
    private fun failed(failure: Failure<E>): Next<S> =
        when (val decision = run.get().supervision?.invoke(failure)) {
            is Schedule.Decision.Continue -> {
                clock.sleep(decision.delay)
                run.updateAndGet {
                    it.copy(state = behaviour.initial, supervision = decision.step, delays = it.delays + decision.delay)
                }
                Next.Stay
            }

            else -> {
                run.updateAndGet { it.copy(failure = failure, stopped = true) }
                Next.Stop
            }
        }

    private fun after(run: Run<M, S, E>, next: Next<S>, message: M): Run<M, S, E> = when (next) {
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
