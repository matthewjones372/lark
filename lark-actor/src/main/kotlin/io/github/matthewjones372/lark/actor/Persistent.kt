package io.github.matthewjones372.lark.actor

import arrow.core.raise.Raise
import io.github.matthewjones372.lark.logError

/**
 * A persistent actor's state: the [value] its events built, the sequence number of the last of them, and the last
 * sequence number it handled from each producer that sends to it reliably (spec 0079).
 */
data class Remembered<S>(val value: S, val sequence: Long, val delivered: Map<String, Long> = emptyMap())

/** How a persistent actor's state becomes bytes for a snapshot, and back. */
interface StateCodec<S> {
    fun encode(state: S): ByteArray

    fun decode(bytes: ByteArray): S
}

/** When a persistent actor saves its state: after each [every]th event, as bytes through [codec] (spec 0074). */
class Snapshotting<S> internal constructor(val every: Long, val codec: StateCodec<S>, val prune: Prune)

/**
 * Whether, and how far, a saved snapshot lets its entity's older events go (specs 0076 and 0077): the feed offset
 * pruning may reach now, or null to keep every event.
 */
fun interface Prune {
    fun readTo(): Long?

    companion object {
        /** Keeps every event. */
        val never: Prune = Prune { null }

        /** Deletes what the snapshot before the newest covers, whatever has read it. */
        val always: Prune = Prune { Long.MAX_VALUE }

        /**
         * As [always], but no further than every one of [names] has read, by the offsets their projections save to
         * [offsets]. A name with no offset saved holds back everything.
         */
        fun after(offsets: OffsetStore, vararg names: String): Prune {
            require(names.isNotEmpty()) { "pruning after no read model is Prune.always" }
            val waited = names.toList()
            return Prune { waited.minOf { offsets.load(it) ?: 0 } }
        }
    }
}

/**
 * A snapshot after every [n]th event, the state written by [codec]. With a [prune] other than [Prune.never], a journal
 * that is a [JournalPruning] deletes, once each snapshot is saved, the events the one before it covers, as far as
 * [prune] allows (specs 0076 and 0077).
 */
fun <S> every(n: Int, codec: StateCodec<S>, prune: Prune = Prune.never): Snapshotting<S> {
    require(n > 0) { "a snapshot every $n events is never" }
    return Snapshotting(n.toLong(), codec, prune)
}

/** What a command does: persist events, or not, and what runs once they are written and applied. */
class Effect<out Ev, S> internal constructor(
    internal val events: List<Ev>,
    internal val next: Next<Nothing>?,
    internal val then: ((S) -> Unit)?,
) {
    /** Runs [action] with the state after the events, once they are written and applied. */
    fun then(action: (state: S) -> Unit): Effect<Ev, S> {
        val before = then
        return Effect(events, next) { state ->
            before?.invoke(state)
            action(state)
        }
    }
}

/** The effects a persistent actor's command can answer. */
class Effects<Ev, S> internal constructor() {
    /** Writes [events] to the journal, then applies them to the state. */
    fun persist(vararg events: Ev): Effect<Ev, S> = Effect(events.toList(), null, null)

    /** Writes nothing, and keeps the state. */
    fun none(): Effect<Ev, S> = Effect(emptyList(), null, null)

    /** The command is not this actor's to handle: it goes where unhandled messages go. */
    fun unhandled(): Effect<Ev, S> = Effect(emptyList(), Next.Unhandled, null)

    /** Writes nothing, and stops the actor. */
    fun stop(): Effect<Ev, S> = Effect(emptyList(), Next.Stop, null)
}

/**
 * A behaviour remembered by its events. On start, and after every restart, it replays its events from its flock's
 * journal through [event] before its first command. A command answers an [Effect]: `persist` writes its events on the
 * step's own thread, applies them, and then runs the effect's `then`; the next command sees the new state. An append
 * that conflicts with another writer is raised as the behaviour's failure, so supervision decides.
 *
 * With [snapshots], and a flock that has a store, a persist that carries the sequence number across a multiple of
 * its `every` saves the state it reached, before `then`; a start loads the newest snapshot and replays only the
 * events after it. A save that throws is logged and the step goes on: the events are already written, and the next
 * multiple saves again.
 *
 * A [Delivered] command whose sequence number is no greater than the last this actor handled from its producer is
 * dropped without a step (spec 0079). One that persists events writes a mark of its delivery in the same append, so
 * the actor remembers it across restarts and moves, as a snapshot does; one that persists nothing is remembered until
 * the actor stops, and handled again after that, where it changes nothing the journal holds.
 */
@Suppress("LongParameterList")
fun <M : Any, Ev, S> persistent(
    id: PersistenceId,
    empty: S,
    codec: EventCodec<Ev>,
    command: Effects<Ev, S>.(ctx: Ctx<M>, state: S, command: M) -> Effect<Ev, S>,
    event: (state: S, event: Ev) -> S,
    snapshots: Snapshotting<S>? = null,
    /**
     * How many of the commands already waiting are decided before one append writes all their events (spec 0085).
     * With more than 1, each command is still decided against the state the one before it left, and each `then` runs
     * after the append, in order; a conflict fails the whole batch.
     */
    batch: Int = 1,
): Behaviour<M, Remembered<S>, JournalConflict> {
    require(batch > 0) { "a batch of $batch commands decides none" }
    val effects = Effects<Ev, S>()
    val steps: (Raise<JournalConflict>.(Ctx<M>, Remembered<S>, List<M>) -> Batched<Remembered<S>>)? =
        if (batch == 1) {
            null
        } else {
            { ctx, remembered, messages ->
                val deciding = Deciding(effects, command, event, codec, ctx, remembered)
                decided(id, snapshots, deciding, ctx, remembered, messages)
            }
        }
    return Behaviour<M, Remembered<S>, JournalConflict>(
        initial = Remembered(empty, 0),
        step = { ctx, remembered, message ->
            val delivery = (message as? Delivered)?.delivery
            if (remembered.handled(delivery)) {
                Next.Stay
            } else {
                val effect = effects.command(ctx, remembered.value, message)
                val after = when {
                    effect.events.isNotEmpty() -> {
                        val written = effect.events.map(codec::encode) + listOfNotNull(delivery?.let(::mark))
                        val sequence = ctx.journal.append(id, remembered.sequence, written).bind()
                        val value = effect.events.fold(remembered.value, event)
                        Remembered(value, sequence, remembered.delivered.after(delivery)).also { reached ->
                            if (snapshots != null) {
                                ctx.snapshots?.snapshot(id, remembered.sequence, reached, snapshots, ctx.journal)
                            }
                        }
                    }

                    delivery != null -> remembered.copy(delivered = remembered.delivered.after(delivery))

                    else -> remembered
                }
                effect.then?.invoke(after.value)
                effect.next ?: if (after === remembered) Next.Stay else Next.Become(after)
            }
        },
        start = { ctx, _ -> Next.Become(recovered(id, empty, codec, event, snapshots, ctx)) },
        signal = null,
        steps = steps,
        batch = batch,
    )
}

/** The newest snapshot, if any, and every event after it folded in: where a start and a restart begin. */
@Suppress("LongParameterList")
private fun <M : Any, Ev, S> recovered(
    id: PersistenceId,
    empty: S,
    codec: EventCodec<Ev>,
    event: (state: S, event: Ev) -> S,
    snapshots: Snapshotting<S>?,
    ctx: Ctx<M>,
): Remembered<S> {
    val from = snapshots?.let { ctx.snapshots?.latest(id) }
        ?.let { decode(snapshots.codec, it.bytes, it.sequence) }
        ?: Remembered(empty, 0)
    val stored = ctx.journal.read(id, from.sequence + 1)
    val first = stored.firstOrNull()?.sequence ?: (from.sequence + 1)
    check(first == from.sequence + 1) {
        "$id cannot be recovered: events ${from.sequence + 1} to ${first - 1} were deleted, and no snapshot covers them"
    }
    return stored.fold(from) { state, kept -> state.replay(kept, codec, event) }
}

/** One command of a batch once decided: what it answered, and the state it left, for its `then`. */
private class Decided<Ev, S>(val effect: Effect<Ev, S>, val after: S)

/** A batch being decided: the state each command leaves for the next, and what the append will write. */
private class Deciding<M : Any, Ev, S>(
    private val effects: Effects<Ev, S>,
    private val command: Effects<Ev, S>.(ctx: Ctx<M>, state: S, command: M) -> Effect<Ev, S>,
    private val event: (state: S, event: Ev) -> S,
    private val codec: EventCodec<Ev>,
    private val ctx: Ctx<M>,
    remembered: Remembered<S>,
) {
    var value: S = remembered.value
    var delivered: Map<String, Long> = remembered.delivered
    val written = ArrayList<ByteArray>()
    val decided = ArrayList<Decided<Ev, S>>()
    val unhandled = ArrayList<Any>()
    var stopped = false

    /** [message] decided against the state so far, unless its delivery was already handled. */
    fun take(message: M) {
        val delivery = (message as? Delivered)?.delivery
        if (delivery == null || delivery.sequence > (delivered[delivery.producer] ?: 0)) decide(message, delivery)
    }

    private fun decide(message: M, delivery: Delivery?) {
        val effect = effects.command(ctx, value, message)
        if (effect.next === Next.Unhandled) {
            unhandled += message
        } else {
            if (effect.events.isNotEmpty()) {
                written += effect.events.map(codec::encode)
                delivery?.let { written += mark(it) }
                value = effect.events.fold(value, event)
            }
            delivered = delivered.after(delivery)
            decided += Decided(effect, value)
            stopped = effect.next === Next.Stop
        }
    }
}

/**
 * A batch of [messages] decided in turn, each against the state the one before left, and every event and delivery
 * mark among them written in one append; then each `then` runs, in order, with the state its own command left.
 */
@Suppress("LongParameterList")
private fun <M : Any, Ev, S> Raise<JournalConflict>.decided(
    id: PersistenceId,
    snapshots: Snapshotting<S>?,
    deciding: Deciding<M, Ev, S>,
    ctx: Ctx<M>,
    remembered: Remembered<S>,
    messages: List<M>,
): Batched<Remembered<S>> {
    var taken = 0
    while (taken < messages.size && !deciding.stopped) deciding.take(messages[taken++])
    val sequence = if (deciding.written.isEmpty()) {
        remembered.sequence
    } else {
        ctx.journal.append(id, remembered.sequence, deciding.written).bind()
    }
    val reached = Remembered(deciding.value, sequence, deciding.delivered)
    if (snapshots != null && sequence != remembered.sequence) {
        ctx.snapshots?.snapshot(id, remembered.sequence, reached, snapshots, ctx.journal)
    }
    deciding.decided.forEach { it.effect.then?.invoke(it.after) }
    val next = when {
        deciding.stopped -> Next.Stop
        reached == remembered -> Next.Stay
        else -> Next.Become(reached)
    }
    return Batched(next, deciding.unhandled, messages.drop(taken))
}

/** Whether [delivery] is one this state already handled: no later than the last from its producer. */
private fun Remembered<*>.handled(delivery: Delivery?): Boolean =
    delivery != null && delivery.sequence <= (delivered[delivery.producer] ?: 0)

/** This state after [kept]: an event applied through [event], or the mark of a delivery remembered. */
private fun <Ev, S> Remembered<S>.replay(kept: StoredEvent, codec: EventCodec<Ev>, event: (S, Ev) -> S): Remembered<S> =
    if (isDeliveryMark(kept.bytes)) {
        val (producer, sequence) = unmark(kept.bytes)
        copy(delivered = delivered + (producer to sequence), sequence = kept.sequence)
    } else {
        copy(value = event(value, codec.decode(kept.bytes)), sequence = kept.sequence)
    }

/** These deliveries, with [delivery] the last handled from its producer. */
private fun Map<String, Long>.after(delivery: Delivery?): Map<String, Long> =
    if (delivery == null) this else this + (delivery.producer to delivery.sequence)

/**
 * Saves [reached] if the persist from [before] crossed a multiple of [how]'s `every`, and then, when [how] prunes,
 * deletes from [journal] the events the snapshot before it covers. A failed save or deletion is logged.
 */
@Suppress("LongParameterList")
private fun <S> SnapshotStore.snapshot(
    id: PersistenceId,
    before: Long,
    reached: Remembered<S>,
    how: Snapshotting<S>,
    journal: Journal,
) {
    if (before / how.every == reached.sequence / how.every) return
    val saved = logged("the snapshot of $id at ${reached.sequence} was not saved; the next one will be") {
        save(id, reached.sequence, encode(how.codec, reached))
    }
    val pruning = journal as? JournalPruning
    if (saved && how.prune !== Prune.never && pruning != null) {
        val upTo = reached.sequence - how.every
        logged("the events of $id up to $upTo were not deleted; the next snapshot will try again") {
            how.prune.readTo()?.let { readTo -> pruning.deleteTo(id, upTo, readTo) }
        }
    }
}

/** Whether [work] finished; what it threw is logged as [failed], except an interrupt, which still stops the step. */
private inline fun logged(failed: String, work: () -> Unit): Boolean =
    try {
        work()
        true
    } catch (stopping: InterruptedException) {
        throw stopping
    } catch (thrown: Exception) {
        logError(failed, thrown)
        false
    }
