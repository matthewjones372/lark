package io.github.matthewjones372.lark.actor

import arrow.core.raise.Raise

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
)

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
