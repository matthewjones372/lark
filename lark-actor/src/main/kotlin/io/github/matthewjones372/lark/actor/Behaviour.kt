package io.github.matthewjones372.lark.actor

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

/** An actor described: where it starts, and what one message does. Nothing runs until something runs it. */
class Behaviour<M : Any, S>(val initial: S, val step: (ctx: Ctx<M>, state: S, message: M) -> Next<S>)

fun <M : Any, S> behaviour(initial: S, step: (ctx: Ctx<M>, state: S, message: M) -> Next<S>): Behaviour<M, S> =
    Behaviour(initial, step)

sealed interface AskFailure {
    data object TimedOut : AskFailure

    data object Stopped : AskFailure

    /** Never the answer from an actor in this process; here so that a remote one does not break a `when`. */
    data object Unreachable : AskFailure
}
