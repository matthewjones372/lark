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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    private val room: Semaphore,
    private val keep: Int,
    private val within: Duration,
    private val full: Counter,
    private val keeping: Keeping<M>,
    private val recovered: CountDownLatch? = null,
) {
    /**
     * Keeps the command [command] builds for entity [to] and returns, or waits up to `within` while the producer
     * keeps as many as it may, and then answers [Full]. [command] runs once, on the caller's thread, so what it
     * throws reaches the caller and nothing is kept. A durable producer encodes the command there too, and so throws
     * for one its codec refuses; it returns once the command is written to its journal, and answers [Full] too if
     * that takes longer than `within`, when such a command may still be written, and then sent (spec 0085).
     */
    fun send(to: String, command: (Delivery) -> M): Either<Full, Unit> {
        if (!room.tryAcquire(within.inWholeNanoseconds, TimeUnit.NANOSECONDS)) return full()
        var kept: CountDownLatch? = null
        try {
            kept = keeping.keep(to, command)
        } finally {
            // A command that failed to build, or found the producer's own mailbox full, holds no room.
            if (kept == null) room.release()
        }
        return if (kept?.await(within.inWholeNanoseconds, TimeUnit.NANOSECONDS) == true) Unit.right() else full()
    }

    private fun full(): Either<Full, Unit> {
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
 * How a [Producer] hands a command to its actor, on the caller's thread: the latch it waits on, or null when the
 * actor's mailbox is full.
 */
internal fun interface Keeping<M : Any> {
    fun keep(to: String, command: (Delivery) -> M): CountDownLatch?
}

/** Already counted down: an in-memory producer's command is kept once its actor has it. */
private val HANDED = CountDownLatch(0)

/**
 * [message] told, or false when this is an actor's thread and the mailbox it reaches is full: a tell from an actor
 * never waits for room, and throws instead.
 */
@Suppress("SwallowedException")
internal fun <M : Any> ActorRef<M>.handed(message: M): Boolean = try {
    tell(message)
    true
} catch (_: IllegalStateException) {
    false
}

/**
 * An in-memory producer's numbering, on the caller's thread: each command is built there, once, with its number, and
 * handed to [actor] under one lock, so the actor has each entity's commands in the order they were numbered.
 */
private class Numbering<M : Any>(private val producer: String, private val actor: ActorRef<Any>) : Keeping<M> {
    private val lock = ReentrantLock()
    private val next = HashMap<String, Long>()

    override fun keep(to: String, command: (Delivery) -> M): CountDownLatch? = lock.withLock {
        val sequence = next[to] ?: 1

        @Suppress("UNCHECKED_CAST")
        val built = command(Delivery(producer, to, sequence, actor as ActorRef<Confirmed>))
        if (!actor.handed(KeepCommand(to, Unconfirmed(sequence, built)))) return null
        next[to] = sequence + 1
        HANDED
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
    val producer = incarnation(id)
    val actor = spawn("producer-$id", producing<M>(resendAfter, room, keep, meters, route))
    return Producer(room, keep, within, meters.full, Numbering<M>(producer, actor))
        .drainedOnClose(this, id, drainWithin)
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
    val actor = spawn("producer-$id", producing<M>(resendAfter, room, keep, meters, route))
    return Producer(room, keep, within, meters.full, Numbering<M>(incarnation(id), actor))
}

internal fun room(keep: Int): Semaphore {
    require(keep > 0) { "a producer that keeps $keep commands can send none" }
    return Semaphore(keep)
}

private fun incarnation(id: String) = "$id/${UUID.randomUUID()}"

/** A command to keep, built and numbered by [Producer.send]. */
private class KeepCommand<M : Any>(val to: String, val kept: Unconfirmed<M>)

/** A command to keep in the journal, encoded by a durable [Producer.send], which waits on [written]. */
internal class KeepDurably(val to: String, val bytes: ByteArray, val written: CountDownLatch)

/** Time to send every entity's unconfirmed command again. */
internal data object ResendUnconfirmed

/** One entity's commands kept, the first of them in flight. */
private data class Outbox<M : Any>(val kept: List<Unconfirmed<M>>)

private data class Unconfirmed<M : Any>(val sequence: Long, val command: M)

/** A producer's instruments (spec 0081): what it keeps unconfirmed, what it sent again, and each `Full`. */
internal class ProducerMeters(val unconfirmed: Gauge, val resent: Counter, val full: Counter)

/** The producer's actor: keeps commands, sends each entity its first, and frees room as they confirm. */
private fun <M : Any> producing(
    resendAfter: Duration,
    room: Semaphore,
    keep: Int,
    meters: ProducerMeters,
    route: (String) -> ActorRef<M>,
): Behaviour<Any, Map<String, Outbox<M>>, Nothing> {
    // A copy that finds the entity's mailbox full is not lost: it is sent again on the next resend.
    fun send(to: String, kept: Unconfirmed<M>) = route(to).handed(kept.command)

    // Room taken is what `send` has kept and no entity has confirmed yet.
    fun measure() = meters.unconfirmed.set((keep - room.availablePermits()).toDouble())
    return behaviour<Any, Map<String, Outbox<M>>>(emptyMap()) { _, queues, message ->
        when (message) {
            is KeepCommand<*> -> {
                @Suppress("UNCHECKED_CAST")
                val asked = message as KeepCommand<M>
                val queue = queues[asked.to] ?: Outbox(emptyList())
                if (queue.kept.isEmpty()) send(asked.to, asked.kept)
                measure()
                become(queues + (asked.to to Outbox(queue.kept + asked.kept)))
            }

            is Confirmed -> {
                val queue = queues[message.to]
                if (queue?.kept?.firstOrNull()?.sequence != message.sequence) {
                    stay()
                } else {
                    room.release()
                    measure()
                    val rest = queue.kept.drop(1)
                    rest.firstOrNull()?.let { send(message.to, it) }
                    become(queues + (message.to to Outbox(rest)))
                }
            }

            ResendUnconfirmed -> {
                resendFirsts(queues, meters) { to, kept -> send(to, kept) }
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
