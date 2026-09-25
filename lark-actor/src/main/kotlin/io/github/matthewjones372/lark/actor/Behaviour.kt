package io.github.matthewjones372.lark.actor

import arrow.core.raise.Raise
import io.github.matthewjones372.lark.Schedule
import kotlin.time.Duration

/** Where an actor lives. A ref is named by one, so that a ref can later name an actor on another node. */
data class Address(val node: String, val path: String, val incarnation: Long)

interface ActorRef<in M : Any> {
    val address: Address

    fun tell(message: M)
}

/** One answer, once. A narrow ref rather than a closure, so that an ask can later cross a node. */
interface Reply<in A : Any> {
    val address: Address

    operator fun invoke(answer: A)
}

interface Ctx<M : Any> {
    val self: ActorRef<M>

    /** Messages this actor sends itself later, each under a key; a stop or a restart cancels them all. */
    val timers: Timers<M>

    /**
     * Tells this actor [message] once nothing has arrived for [after], and again only after the next message: once
     * per silence. Any message resets it, a timer's included; a restart turns it off.
     */
    fun receiveTimeout(after: Duration, message: M)

    /** Turns the receive timeout off: `receiveTimeout(null)`. */
    fun receiveTimeout(off: Nothing?)

    /**
     * [state], with [timers] that belong to it: they end when the actor next becomes a state of another class, and a
     * copy of this one keeps them. Becoming a state with timers again replaces the ones it had. Only a step that
     * returns this becomes it; timers from one it does not return never start.
     */
    fun <S> become(state: S, timers: StateTimers<M>.() -> Unit): Next<S>

    /**
     * Keeps [message] to handle later, when [unstashAll] puts it back. The stash is bounded, and keeping one more than
     * it holds fails the step. A restart or a stop drops it.
     */
    fun stash(message: M)

    /** Puts every kept message back, to be handled before anything in the mailbox, in the order kept. */
    fun unstashAll()

    /** Hears [Signal.Terminated] once [ref] stops, once however often it is asked; at once if it already has. */
    fun watch(ref: ActorRef<*>)

    /**
     * A child: named under this actor, and stopped before it, whether this actor stops, fails or is restarted. A
     * restarted parent has none until its step spawns them again.
     */
    fun <C : Any, T, F> spawn(
        name: String,
        behaviour: Behaviour<C, T, F>,
        restart: Schedule<Failure<F>, *>? = null,
    ): ActorRef<C>
}

/**
 * An actor's own timers. A timer's message arrives through the mailbox and is handled by the step like any other.
 * Starting a key that is running replaces it, and a message from a cancelled or replaced timer never arrives, even
 * one already in the mailbox.
 */
interface Timers<in M : Any> {
    /** Tells this actor [message] once [delay] has passed on its flock's clock; at once when it is not positive. */
    fun after(key: Any, delay: Duration, message: M)

    /**
     * Tells this actor [message] every [interval] until [key] is cancelled or started again. Each is measured from
     * when the last was handled, so an actor that falls behind is never sent a burst to catch up.
     */
    fun every(key: Any, interval: Duration, message: M)

    /** Cancels the timer under [key], if one is running. */
    fun cancel(key: Any)
}

/** The timers of one state, started by `ctx.become(state) { … }`; they have no key, since the state is theirs. */
interface StateTimers<in M : Any> {
    fun after(delay: Duration, message: M)

    fun every(interval: Duration, message: M)
}

/** What happens to an actor rather than what is sent to it, handled beside its messages with no `else`. */
sealed interface Signal {
    /** The actor is ending, by `stop()`, a failure with nothing left to restart it, or its flock closing. */
    data object Stopping : Signal

    /** An actor this one watches has stopped. */
    data class Terminated(val ref: ActorRef<*>) : Signal
}

sealed interface Next<out S> {
    data object Stay : Next<Nothing>

    data class Become<out S>(val state: S) : Next<S>

    data object Stop : Next<Nothing>

    /** The state stays, and the message goes where unhandled messages go. */
    data object Unhandled : Next<Nothing>
}

fun stay(): Next<Nothing> = Next.Stay

fun <S> become(state: S): Next<S> = Next.Become(state)

fun stop(): Next<Nothing> = Next.Stop

fun unhandled(): Next<Nothing> = Next.Unhandled

/**
 * An actor described: where it starts, and what one message does. Nothing runs until something runs it. A step
 * leaves with a declared failure by raising [E]; one that never does is `Behaviour<M, S, Nothing>`.
 */
class Behaviour<M : Any, S, out E>(
    val initial: S,
    val step: Raise<E>.(ctx: Ctx<M>, state: S, message: M) -> Next<S>,
    /** How it takes a [Signal]; with none, a signal changes nothing. */
    val signal: (Raise<E>.(ctx: Ctx<M>, state: S, signal: Signal) -> Next<S>)? = null,
)

/** This behaviour, taking signals with [handler]. */
fun <M : Any, S, E> Behaviour<M, S, E>.onSignal(
    handler: Raise<E>.(ctx: Ctx<M>, state: S, signal: Signal) -> Next<S>,
): Behaviour<M, S, E> = Behaviour(initial, step, handler)

/**
 * A behaviour whose step may raise [E]. Name all three types, `behaviour<M, S, E>(…)`: left to inference, a call
 * resolves to the overload that never raises.
 */
@JvmName("raising")
fun <M : Any, S, E> behaviour(
    initial: S,
    step: Raise<E>.(ctx: Ctx<M>, state: S, message: M) -> Next<S>,
): Behaviour<M, S, E> = Behaviour(initial, step)

/** A behaviour that never raises, so nothing has to name a failure type it does not have. */
fun <M : Any, S> behaviour(
    initial: S,
    step: Raise<Nothing>.(ctx: Ctx<M>, state: S, message: M) -> Next<S>,
): Behaviour<M, S, Nothing> = Behaviour(initial, step)

/** Why an actor stopped other than by answering `stop()`: a failure it declared, or a throw nobody declared. */
sealed interface Failure<out E> {
    data class Raised<out E>(val error: E) : Failure<E>

    data class Thrown(val throwable: Throwable) : Failure<Nothing>
}

/**
 * The one boundary a step's `raise` unwinds to. Each actor has its own, and a raise carries it, so that a raise
 * belonging to an `either` inside the step is that `either`'s and never mistaken for the actor's.
 */
internal class StepRaise<E> : Raise<E> {
    override fun raise(r: E): Nothing = throw Raised(r, this)

    /** What [step] returned, or [raised] of what it raised here. Inline, so a step allocates nothing. */
    inline fun <A> guarded(step: Raise<E>.() -> A, raised: (E) -> A): A =
        try {
            step(this)
        } catch (failure: Raised) {
            if (failure.by !== this) throw failure
            @Suppress("UNCHECKED_CAST")
            raised(failure.error as E)
        }
}

/** Carries a raise to its [StepRaise]; no stack trace, since it is control flow, not a fault. */
internal class Raised(val error: Any?, val by: StepRaise<*>) : RuntimeException(null, null, false, false)

sealed interface AskFailure {
    data object TimedOut : AskFailure

    data object Stopped : AskFailure

    /** Never the answer from an actor in this process; here so that a remote one does not break a `when`. */
    data object Unreachable : AskFailure
}
