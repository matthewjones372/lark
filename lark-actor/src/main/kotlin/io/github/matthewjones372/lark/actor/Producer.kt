package io.github.matthewjones372.lark.actor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.Counter
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.Gauge
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logWarn
import java.util.UUID
import java.util.concurrent.CountDownLatch
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
    private val full: Counter,
    private val durable: Boolean = false,
    private val recovered: CountDownLatch? = null,
) {
    /**
     * Keeps the command [command] builds for entity [to] and returns, or waits up to `within` while the producer
     * keeps as many as it may, and then answers [Full]. [command] may be called again for each resend. A durable
     * producer returns once the command is written to its journal, and answers [Full] too if that takes longer than
     * `within`; such a command may still be written, and then sent (spec 0085).
     */
    fun send(to: String, command: (Delivery) -> M): Either<Full, Unit> {
        if (!room.tryAcquire(within.inWholeNanoseconds, TimeUnit.NANOSECONDS)) {
            full.increment()
            return Full.left()
        }
        if (!durable) {
            actor.tell(KeepCommand(to, command))
            return Unit.right()
        }
        val written = CountDownLatch(1)
        actor.tell(KeepDurably(to, command, written))
        if (written.await(within.inWholeNanoseconds, TimeUnit.NANOSECONDS)) return Unit.right()
        full.increment()
        return Full.left()
    }

    /**
     * Whether every command kept was confirmed within [within]: for a service about to stop, or a test that waits for
     * its commands to land. A `send` while it waits may make it wait longer.
     */
    fun drain(within: Duration): Boolean {
        // A durable producer's recovered commands hold room only once its actor has claimed it.
        if (recovered?.await(within.inWholeNanoseconds, TimeUnit.NANOSECONDS) == false) return false
        if (!room.tryAcquire(keep, within.inWholeNanoseconds, TimeUnit.NANOSECONDS)) return false
        room.release(keep)
        return true
    }
}

/**
 * A producer in this flock that sends to the entity [route] names. Its deliveries carry [id] and an incarnation of
 * its own, so a producer started again under the same [id] is not taken for a duplicate of the one before. It keeps
 * at most [keep] unconfirmed commands, and a `send` waits up to [within] for room. When the flock closes, it waits
 * up to [drainWithin] for what it keeps to be confirmed before its actor stops, so a service that stops does not lose
 * what it has sent (spec 0080); a [drainWithin] of zero loses it, as a crash does.
 */
@Suppress("LongParameterList")
fun <F, M : Any> Flock<F>.producer(
    id: String,
    resendAfter: Duration = 2.seconds,
    keep: Int = 1_000,
    within: Duration = 5.seconds,
    drainWithin: Duration = 30.seconds,
    route: (to: String) -> ActorRef<M>,
): Producer<M> {
    val room = room(keep)
    val meters = meters(id)
    val actor = spawn("producer-$id", producing(incarnation(id), resendAfter, room, keep, meters, route))
    return Producer<M>(actor, room, keep, within, meters.full).drainedOnClose(this, id, drainWithin)
}

/** This flock's instruments for the producer [id]. */
internal fun Flock<*>.meters(id: String) = ProducerMeters(
    unconfirmed = gauge("lark.delivery.unconfirmed", "producer" to id),
    resent = counter("lark.delivery.resent", "producer" to id),
    full = counter("lark.delivery.full", "producer" to id),
)

/** This producer, waiting up to [drainWithin] for what it keeps when [flock] closes (spec 0080). */
internal fun <M : Any> Producer<M>.drainedOnClose(flock: Flock<*>, id: String, drainWithin: Duration): Producer<M> {
    if (drainWithin.isPositive()) {
        flock.onClose {
            val drained = drain(drainWithin)
            if (!drained) logWarn("producer $id closed with commands unconfirmed after $drainWithin")
        }
    }
    return this
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
    val meters = ProducerMeters(Gauge { }, Counter { }, Counter { })
    val actor = spawn("producer-$id", producing(incarnation(id), resendAfter, room, keep, meters, route))
    return Producer(actor, room, keep, within, meters.full)
}

internal fun room(keep: Int): Semaphore {
    require(keep > 0) { "a producer that keeps $keep commands can send none" }
    return Semaphore(keep)
}

private fun incarnation(id: String) = "$id/${UUID.randomUUID()}"

/** A command to keep, from [Producer.send]. */
private class KeepCommand<M : Any>(val to: String, val command: (Delivery) -> M)

/** A command to keep in the journal, from a durable [Producer.send], which waits on [written]. */
internal class KeepDurably<M : Any>(val to: String, val command: (Delivery) -> M, val written: CountDownLatch)

/** Time to send every entity's unconfirmed command again. */
internal data object ResendUnconfirmed

/**
 * One entity's commands: the number the next one takes, and those kept, the first of them in flight. It stays once
 * none are kept, so the numbering goes on.
 */
private data class Outbox<M : Any>(val next: Long, val kept: List<Unconfirmed<M>>)

private data class Unconfirmed<M : Any>(val sequence: Long, val command: (Delivery) -> M)

/** A producer's instruments (spec 0081): what it keeps unconfirmed, what it sent again, and each `Full`. */
internal class ProducerMeters(val unconfirmed: Gauge, val resent: Counter, val full: Counter)

/** The producer's actor: numbers and keeps commands, sends each entity its first, and frees room as they confirm. */
@Suppress("LongParameterList")
private fun <M : Any> producing(
    producer: String,
    resendAfter: Duration,
    room: Semaphore,
    keep: Int,
    meters: ProducerMeters,
    route: (String) -> ActorRef<M>,
): Behaviour<Any, Map<String, Outbox<M>>, Nothing> {
    fun Ctx<Any>.send(to: String, kept: Unconfirmed<M>) =
        route(to).tell(kept.command(Delivery(producer, to, kept.sequence, self)))

    // Room taken is what `send` has kept and no entity has confirmed yet.
    fun measure() = meters.unconfirmed.set((keep - room.availablePermits()).toDouble())
    return behaviour<Any, Map<String, Outbox<M>>>(emptyMap()) { ctx, queues, message ->
        when (message) {
            is KeepCommand<*> -> {
                @Suppress("UNCHECKED_CAST")
                val asked = message as KeepCommand<M>
                val queue = queues[asked.to] ?: Outbox(1, emptyList())
                val kept = Unconfirmed(queue.next, asked.command)
                if (queue.kept.isEmpty()) ctx.send(asked.to, kept)
                measure()
                become(queues + (asked.to to Outbox(queue.next + 1, queue.kept + kept)))
            }

            is Confirmed -> {
                val queue = queues[message.to]
                if (queue?.kept?.firstOrNull()?.sequence != message.sequence) {
                    stay()
                } else {
                    room.release()
                    measure()
                    val rest = queue.kept.drop(1)
                    rest.firstOrNull()?.let { ctx.send(message.to, it) }
                    become(queues + (message.to to queue.copy(kept = rest)))
                }
            }

            ResendUnconfirmed -> {
                resendFirsts(queues, meters) { to, kept -> ctx.send(to, kept) }
                stay()
            }

            else -> unhandled()
        }
    }.onStart { ctx -> ctx.timers.every(ResendUnconfirmed, resendAfter, ResendUnconfirmed) }
}

/** Sends each entity's first unconfirmed command again, counting each. */
private fun <M : Any> resendFirsts(
    queues: Map<String, Outbox<M>>,
    meters: ProducerMeters,
    send: (String, Unconfirmed<M>) -> Unit,
) = queues.forEach { (to, queue) ->
    queue.kept.firstOrNull()?.let {
        meters.resent.increment()
        send(to, it)
    }
}
