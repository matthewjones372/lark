package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.logError

/** A persistent actor's state: the [value] its events built, and the sequence number of the last of them. */
data class Remembered<S>(val value: S, val sequence: Long)

/** How a persistent actor's state becomes bytes for a snapshot, and back. */
interface StateCodec<S> {
    fun encode(state: S): ByteArray

    fun decode(bytes: ByteArray): S
}

/** When a persistent actor saves its state: after each [every]th event, as bytes through [codec] (spec 0074). */
class Snapshotting<S> internal constructor(val every: Long, val codec: StateCodec<S>)

/** A snapshot after every [n]th event, the state written by [codec]. */
fun <S> every(n: Int, codec: StateCodec<S>): Snapshotting<S> {
    require(n > 0) { "a snapshot every $n events is never" }
    return Snapshotting(n.toLong(), codec)
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
 */
@Suppress("LongParameterList")
fun <M : Any, Ev, S> persistent(
    id: PersistenceId,
    empty: S,
    codec: EventCodec<Ev>,
    command: Effects<Ev, S>.(ctx: Ctx<M>, state: S, command: M) -> Effect<Ev, S>,
    event: (state: S, event: Ev) -> S,
    snapshots: Snapshotting<S>? = null,
): Behaviour<M, Remembered<S>, JournalConflict> {
    val effects = Effects<Ev, S>()
    return Behaviour(
        initial = Remembered(empty, 0),
        step = { ctx, remembered, message ->
            val effect = effects.command(ctx, remembered.value, message)
            val after = if (effect.events.isEmpty()) {
                remembered
            } else {
                val sequence = ctx.journal.append(id, remembered.sequence, effect.events.map(codec::encode)).bind()
                Remembered(effect.events.fold(remembered.value, event), sequence).also { reached ->
                    if (snapshots != null) ctx.snapshots?.snapshot(id, remembered.sequence, reached, snapshots)
                }
            }
            effect.then?.invoke(after.value)
            effect.next ?: if (after === remembered) Next.Stay else Next.Become(after)
        },
        start = { ctx, _ ->
            val from = snapshots?.let { ctx.snapshots?.latest(id) }
                ?.let { Remembered(snapshots.codec.decode(it.bytes), it.sequence) }
                ?: Remembered(empty, 0)
            val stored = ctx.journal.read(id, from.sequence + 1)
            val value = stored.fold(from.value) { state, kept -> event(state, codec.decode(kept.bytes)) }
            Next.Become(Remembered(value, stored.lastOrNull()?.sequence ?: from.sequence))
        },
    )
}

/** Saves [reached] if the persist from [before] crossed a multiple of [how]'s `every`; a failed save is logged. */
private fun <S> SnapshotStore.snapshot(id: PersistenceId, before: Long, reached: Remembered<S>, how: Snapshotting<S>) {
    if (before / how.every == reached.sequence / how.every) return
    try {
        save(id, reached.sequence, how.codec.encode(reached.value))
    } catch (stopping: InterruptedException) {
        throw stopping
    } catch (failed: Exception) {
        logError("the snapshot of $id at ${reached.sequence} was not saved; the next one will be", failed)
    }
}
