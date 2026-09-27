package io.github.matthewjones372.lark.actor

/**
 * Where a command sent reliably came from (spec 0079): its [producer], the entity it is [to], its [sequence] among
 * that producer's commands to that entity, counting from 1, and where to confirm it.
 */
data class Delivery(val producer: String, val to: String, val sequence: Long, val confirmTo: ActorRef<Confirmed>) {
    /** Tells the producer this command was handled, so it stops sending it again. */
    fun confirm() = confirmTo.tell(Confirmed(to, sequence))
}

/**
 * A command a producer may send more than once. The command type carries its [delivery], so an entity's protocol
 * stays its own: a command sent plainly is one that does not implement this.
 */
interface Delivered {
    val delivery: Delivery
}

/** The command numbered [sequence] to entity [to] was handled; its producer need not send it again. */
data class Confirmed(val to: String, val sequence: Long)

/**
 * [behaviour], confirming each [Delivered] command once its step has returned, whatever the step answered. A step
 * that fails confirms nothing, so the producer sends the command again. Duplicates reach the step: a persistent
 * behaviour drops those it remembers, and any other must handle a command twice as once.
 */
fun <M : Any, S, E> delivered(behaviour: Behaviour<M, S, E>): Behaviour<M, S, E> = Behaviour(
    initial = behaviour.initial,
    step = { ctx, state, message ->
        behaviour.step(this, ctx, state, message).also { if (message is Delivered) message.delivery.confirm() }
    },
    signal = behaviour.signal,
    start = behaviour.start,
)
