package io.github.matthewjones372.lark.actor

import arrow.core.raise.either
import io.github.matthewjones372.lark.Counter
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.Gauge
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.increment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A producer whose unconfirmed commands are kept in this flock's journal (spec 0085), so a crash loses none of them:
 * started again under the same [id], on this node or another, it sends each entity's first unconfirmed command again
 * and numbers on from where it stopped. Its deliveries carry [id] alone, so an entity's deduplication holds across
 * the restart. [codec] keeps each command with its delivery blanked, and a command is sent again through
 * [Delivered.redeliver]. Two producers running under one [id] at once conflict, as two writers for one entity do.
 * It snapshots every 1,000 events and prunes what a snapshot covers, where the flock's stores allow. A journal that
 * throws restarts its actor, 100 ms after the first throw and doubling to 5 s, and the restart replays what was written
 * (spec 0098).
 */
@Suppress("LongParameterList")
fun <F, M : Any> Flock<F>.durableProducer(
    id: String,
    codec: EventCodec<M>,
    resendAfter: Duration = 2.seconds,
    keep: Int = 1_000,
    within: Duration = 5.seconds,
    drainWithin: Duration = 30.seconds,
    route: (to: String) -> ActorRef<M>,
): Producer<M> {
    val room = room(keep)
    val meters = meters(id)
    val recovered = CountDownLatch(1)
    val actor = spawn(
        "producer-$id",
        outbox(id, codec, resendAfter, room, keep, meters, recovered, route),
        restart = backoff(),
    )
    return Producer<M>(actor, room, keep, within, meters.full, durable = true, recovered = recovered)
        .drainedOnClose(this, id, drainWithin)
}

/** A durable producer among these test actors, as [Flock.durableProducer]; its resends wait on [TestActors.advance]. */
@Suppress("LongParameterList")
fun <M : Any> TestActors.durableProducer(
    id: String,
    codec: EventCodec<M>,
    resendAfter: Duration = 2.seconds,
    keep: Int = 1_000,
    within: Duration = 5.seconds,
    route: (to: String) -> ActorRef<M>,
): Producer<M> {
    val room = room(keep)
    val meters = ProducerMeters(Gauge { }, Counter { }, Counter { })
    val recovered = CountDownLatch(1)
    val actor = spawn(
        "producer-$id",
        outbox(id, codec, resendAfter, room, keep, meters, recovered, route),
        restart = backoff(),
    )
    return Producer(actor, room, keep, within, meters.full, durable = true, recovered = recovered)
}

/** A durable producer's restarts: short enough that a blip costs little, long enough not to hammer a dead journal. */
private fun <E> backoff(): Schedule<Failure<E>, Duration> =
    Schedule.exponential<Failure<E>>(100.milliseconds).delayed { _, delay -> minOf(delay, 5.seconds) }

/** What a durable producer writes: a command kept, as bytes, and a command confirmed. */
private sealed interface OutboxEvent {
    data class Kept(val to: String, val sequence: Long, val bytes: ByteArray) : OutboxEvent

    data class Done(val to: String, val sequence: Long) : OutboxEvent
}

/** One entity's commands in a durable producer: the number the next takes, and those kept, as the codec wrote them. */
private data class Stored(val next: Long, val kept: List<Pair<Long, ByteArray>>)

private typealias Outboxes = Map<String, Stored>

private const val KEPT = 1
private const val DONE = 2
private const val SNAPSHOT_EVERY = 1_000

/** Sent at once after a start: claim room for what it recovered, and send it. */
private data object Recovered

/** The durable producer's actor: [producing]'s protocol, with each command kept and confirmed in the journal first. */
@Suppress("LongParameterList")
private fun <M : Any> outbox(
    producer: String,
    codec: EventCodec<M>,
    resendAfter: Duration,
    room: Semaphore,
    keep: Int,
    meters: ProducerMeters,
    recovered: CountDownLatch,
    route: (String) -> ActorRef<M>,
): Behaviour<Any, Remembered<Outboxes>, JournalConflict> {
    val steps = OutboxSteps(producer, codec, room, keep, meters, route)
    return persistent<Any, OutboxEvent, Outboxes>(
        id = PersistenceId("lark-producer", producer),
        empty = emptyMap(),
        codec = OutboxEventCodec,
        snapshots = every(SNAPSHOT_EVERY, OutboxesCodec, prune = Prune.always),
        command = { ctx, outboxes, message ->
            when (message) {
                is KeepDurably<*> -> steps.keep(this, ctx, outboxes, message)

                is Confirmed -> steps.confirmed(this, ctx, outboxes, message)

                ResendUnconfirmed -> none().then { now -> steps.resend(ctx, now) }

                Recovered -> none().then { now ->
                    steps.claim(now)
                    recovered.countDown()
                    steps.resend(ctx, now)
                }

                else -> unhandled()
            }
        },
        event = ::applied,
    ).onThrow { message -> if (message is KeepDurably<*>) steps.unwritten(message) }.onStart { ctx ->
        ctx.timers.every(ResendUnconfirmed, resendAfter, ResendUnconfirmed)
        // What the start recovered holds room, and goes out at once rather than a resendAfter later.
        ctx.timers.after(Recovered, Duration.ZERO, Recovered)
    }
}

/**
 * This behaviour, telling [failed] of each message whose step throws, on the throw's way to supervision. A raise is
 * not a throw, and goes on untold.
 */
// A journal may throw anything, and whatever it threw goes on to supervision unchanged.
@Suppress("TooGenericExceptionCaught")
private fun <M : Any, S, E> Behaviour<M, S, E>.onThrow(failed: (M) -> Unit): Behaviour<M, S, E> {
    val inner = step
    return Behaviour(
        initial = initial,
        step = { ctx, state, message ->
            try {
                either<E, Next<S>> { inner(this, ctx, state, message) }
            } catch (thrown: Exception) {
                failed(message)
                throw thrown
            }.bind()
        },
        signal = signal,
        start = start,
        steps = steps,
        batch = batch,
    )
}

/** What a durable producer does with each message; the journal has the rest. */
@Suppress("LongParameterList")
private class OutboxSteps<M : Any>(
    private val producer: String,
    private val codec: EventCodec<M>,
    private val room: Semaphore,
    private val keep: Int,
    private val meters: ProducerMeters,
    private val route: (String) -> ActorRef<M>,
) {
    // The commands that hold room: each kept here, and each recovered that there was room for, so room never
    // exceeds keep and a drain waits for what was recovered as well as what was sent.
    private val holding = ConcurrentHashMap.newKeySet<Pair<String, Long>>()

    /** Takes room for each recovered command that holds none yet, as far as there is room. */
    fun claim(outboxes: Outboxes) {
        outboxes.forEach { (to, stored) ->
            stored.kept.forEach { (sequence, _) ->
                if (to to sequence !in holding && room.tryAcquire()) holding += to to sequence
            }
        }
        measure()
    }

    fun keep(
        effects: Effects<OutboxEvent, Outboxes>,
        ctx: Ctx<Any>,
        outboxes: Outboxes,
        message: KeepDurably<*>,
    ): Effect<OutboxEvent, Outboxes> {
        @Suppress("UNCHECKED_CAST")
        val asked = message as KeepDurably<M>
        val sequence = outboxes[asked.to]?.next ?: 1
        val bytes = codec.encode(asked.command(Delivery(producer, asked.to, sequence, Delivery.NoOne)))
        return effects.persist(OutboxEvent.Kept(asked.to, sequence, bytes)).then { after ->
            // Written: from here a throw leaves its room held, for the restart's replay to find it.
            holding += asked.to to sequence
            asked.written.countDown()
            if (after.getValue(asked.to).kept.size == 1) ctx.send(asked.to, sequence, bytes)
            measure()
        }
    }

    /** Frees the room [asked] took, when the step that was to write it threw first. */
    fun unwritten(asked: KeepDurably<*>) {
        if (asked.written.count > 0) room.release()
        measure()
    }

    fun confirmed(
        effects: Effects<OutboxEvent, Outboxes>,
        ctx: Ctx<Any>,
        outboxes: Outboxes,
        confirmed: Confirmed,
    ): Effect<OutboxEvent, Outboxes> {
        if (outboxes[confirmed.to]?.kept?.firstOrNull()?.first != confirmed.sequence) return effects.none()
        return effects.persist(OutboxEvent.Done(confirmed.to, confirmed.sequence)).then { after ->
            if (holding.remove(confirmed.to to confirmed.sequence)) room.release()
            measure()
            ctx.sendFirst(after, confirmed.to)
        }
    }

    fun resend(ctx: Ctx<Any>, outboxes: Outboxes) = outboxes.forEach { (to, stored) ->
        if (stored.kept.isNotEmpty()) meters.resent.increment()
        ctx.sendFirst(outboxes, to)
    }

    private fun measure() = meters.unconfirmed.set((keep - room.availablePermits()).toDouble())

    private fun Ctx<Any>.sendFirst(outboxes: Outboxes, to: String) =
        outboxes[to]?.kept?.firstOrNull()?.let { (sequence, bytes) -> send(to, sequence, bytes) }

    private fun Ctx<Any>.send(to: String, sequence: Long, bytes: ByteArray) {
        val kept = codec.decode(bytes)
        check(kept is Delivered) { "a durable producer sends only Delivered commands, not $kept" }
        @Suppress("UNCHECKED_CAST")
        route(to).tell(kept.redeliver(Delivery(producer, to, sequence, self)) as M)
    }
}

/** [outboxes] after [event]. */
private fun applied(outboxes: Outboxes, event: OutboxEvent): Outboxes = when (event) {
    is OutboxEvent.Kept -> {
        val stored = outboxes[event.to] ?: Stored(1, emptyList())
        outboxes + (event.to to Stored(event.sequence + 1, stored.kept + (event.sequence to event.bytes)))
    }

    is OutboxEvent.Done -> {
        val stored = outboxes.getValue(event.to)
        outboxes + (event.to to stored.copy(kept = stored.kept.filterNot { it.first == event.sequence }))
    }
}

private object OutboxEventCodec : EventCodec<OutboxEvent> {
    override fun encode(event: OutboxEvent): ByteArray = written { data ->
        when (event) {
            is OutboxEvent.Kept -> {
                data.writeInt(KEPT)
                data.writeUTF(event.to)
                data.writeLong(event.sequence)
                data.writeInt(event.bytes.size)
                data.write(event.bytes)
            }

            is OutboxEvent.Done -> {
                data.writeInt(DONE)
                data.writeUTF(event.to)
                data.writeLong(event.sequence)
            }
        }
    }

    override fun decode(bytes: ByteArray): OutboxEvent = read(bytes) { data ->
        when (val tag = data.readInt()) {
            KEPT -> OutboxEvent.Kept(data.readUTF(), data.readLong(), ByteArray(data.readInt()).also(data::readFully))
            DONE -> OutboxEvent.Done(data.readUTF(), data.readLong())
            else -> error("no outbox event has the tag $tag")
        }
    }
}

private object OutboxesCodec : StateCodec<Outboxes> {
    override fun encode(state: Outboxes): ByteArray = written { data ->
        data.writeInt(state.size)
        state.forEach { (to, stored) ->
            data.writeUTF(to)
            data.writeLong(stored.next)
            data.writeInt(stored.kept.size)
            stored.kept.forEach { (sequence, bytes) ->
                data.writeLong(sequence)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }
    }

    override fun decode(bytes: ByteArray): Outboxes = read(bytes) { data ->
        List(data.readInt()) {
            data.readUTF() to Stored(
                data.readLong(),
                List(data.readInt()) { data.readLong() to ByteArray(data.readInt()).also(data::readFully) },
            )
        }.toMap()
    }
}

private fun written(write: (DataOutputStream) -> Unit): ByteArray =
    ByteArrayOutputStream().also { out -> DataOutputStream(out).use(write) }.toByteArray()

private fun <A> read(bytes: ByteArray, read: (DataInputStream) -> A): A =
    DataInputStream(ByteArrayInputStream(bytes)).use(read)
