package io.github.matthewjones372.lark.actor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.Flock
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A producer already keeps as many unconfirmed commands as it may, and none was confirmed in time (spec 0079). */
data object Full

/**
 * Sends commands at least once (spec 0079). Each command to an entity is numbered and kept until the entity confirms
 * it, and sent again every `resendAfter` until then, so a move or a passivation that loses a copy makes it late, not
 * lost. An entity has one command in flight at a time: the next is sent once the one before it is confirmed, so they
 * arrive in the order they were sent, however many copies are lost.
 */
class Producer<M : Any> internal constructor(
    private val actor: ActorRef<Any>,
    private val room: Semaphore,
    private val keep: Int,
    private val within: Duration,
) {
    /**
     * Keeps the command [command] builds for entity [to] and returns, or waits up to `within` while the producer
     * keeps as many as it may, and then answers [Full]. [command] may be called again for each resend.
     */
    fun send(to: String, command: (Delivery) -> M): Either<Full, Unit> {
        if (!room.tryAcquire(within.inWholeNanoseconds, TimeUnit.NANOSECONDS)) return Full.left()
        actor.tell(KeepCommand(to, command))
        return Unit.right()
    }

    /**
     * Whether every command kept was confirmed within [within]: for a service about to stop, or a test that waits for
     * its commands to land. A `send` while it waits may make it wait longer.
     */
    fun drain(within: Duration): Boolean {
        if (!room.tryAcquire(keep, within.inWholeNanoseconds, TimeUnit.NANOSECONDS)) return false
        room.release(keep)
        return true
    }
}

/**
 * A producer in this flock that sends to the entity [route] names. Its deliveries carry [id] and an incarnation of
 * its own, so a producer started again under the same [id] is not taken for a duplicate of the one before. It keeps
 * at most [keep] unconfirmed commands, and a `send` waits up to [within] for room.
 */
fun <F, M : Any> Flock<F>.producer(
    id: String,
    resendAfter: Duration = 2.seconds,
    keep: Int = 1_000,
    within: Duration = 5.seconds,
    route: (to: String) -> ActorRef<M>,
): Producer<M> {
    val room = room(keep)
    return Producer(spawn("producer-$id", producing(incarnation(id), resendAfter, room, route)), room, keep, within)
}

/** A producer among these test actors, as [Flock.producer]; its resends wait on [TestActors.advance]. */
fun <M : Any> TestActors.producer(
    id: String,
    resendAfter: Duration = 2.seconds,
    keep: Int = 1_000,
    within: Duration = 5.seconds,
    route: (to: String) -> ActorRef<M>,
): Producer<M> {
    val room = room(keep)
    return Producer(spawn("producer-$id", producing(incarnation(id), resendAfter, room, route)), room, keep, within)
}

private fun room(keep: Int): Semaphore {
    require(keep > 0) { "a producer that keeps $keep commands can send none" }
    return Semaphore(keep)
}

private fun incarnation(id: String) = "$id/${UUID.randomUUID()}"

/** A command to keep, from [Producer.send]. */
private class KeepCommand<M : Any>(val to: String, val command: (Delivery) -> M)

/** Time to send every entity's unconfirmed command again. */
private data object ResendUnconfirmed

/**
 * One entity's commands: the number the next one takes, and those kept, the first of them in flight. It stays once
 * none are kept, so the numbering goes on.
 */
private data class Outbox<M : Any>(val next: Long, val kept: List<Unconfirmed<M>>)

private data class Unconfirmed<M : Any>(val sequence: Long, val command: (Delivery) -> M)

/** The producer's actor: numbers and keeps commands, sends each entity its first, and frees room as they confirm. */
private fun <M : Any> producing(
    producer: String,
    resendAfter: Duration,
    room: Semaphore,
    route: (String) -> ActorRef<M>,
): Behaviour<Any, Map<String, Outbox<M>>, Nothing> {
    fun Ctx<Any>.send(to: String, kept: Unconfirmed<M>) =
        route(to).tell(kept.command(Delivery(producer, to, kept.sequence, self)))
    return behaviour<Any, Map<String, Outbox<M>>>(emptyMap()) { ctx, queues, message ->
        when (message) {
            is KeepCommand<*> -> {
                @Suppress("UNCHECKED_CAST")
                val keep = message as KeepCommand<M>
                val queue = queues[keep.to] ?: Outbox(1, emptyList())
                val kept = Unconfirmed(queue.next, keep.command)
                if (queue.kept.isEmpty()) ctx.send(keep.to, kept)
                become(queues + (keep.to to Outbox(queue.next + 1, queue.kept + kept)))
            }

            is Confirmed -> {
                val queue = queues[message.to]
                if (queue?.kept?.firstOrNull()?.sequence != message.sequence) {
                    stay()
                } else {
                    room.release()
                    val rest = queue.kept.drop(1)
                    rest.firstOrNull()?.let { ctx.send(message.to, it) }
                    become(queues + (message.to to queue.copy(kept = rest)))
                }
            }

            ResendUnconfirmed -> {
                queues.forEach { (to, queue) -> queue.kept.firstOrNull()?.let { ctx.send(to, it) } }
                stay()
            }

            else -> unhandled()
        }
    }.onStart { ctx -> ctx.timers.every(ResendUnconfirmed, resendAfter, ResendUnconfirmed) }
}
