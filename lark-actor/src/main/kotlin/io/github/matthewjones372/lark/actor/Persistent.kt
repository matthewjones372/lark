package io.github.matthewjones372.lark.actor

/** A persistent actor's state: the [value] its events built, and the sequence number of the last of them. */
data class Remembered<S>(val value: S, val sequence: Long)

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
 */
fun <M : Any, Ev, S> persistent(
    id: PersistenceId,
    empty: S,
    codec: EventCodec<Ev>,
    command: Effects<Ev, S>.(ctx: Ctx<M>, state: S, command: M) -> Effect<Ev, S>,
    event: (state: S, event: Ev) -> S,
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
                Remembered(effect.events.fold(remembered.value, event), sequence)
            }
            effect.then?.invoke(after.value)
            effect.next ?: if (after === remembered) Next.Stay else Next.Become(after)
        },
        start = { ctx, _ ->
            val stored = ctx.journal.read(id)
            val value = stored.fold(empty) { state, kept -> event(state, codec.decode(kept.bytes)) }
            Next.Become(Remembered(value, stored.lastOrNull()?.sequence ?: 0))
        },
    )
}
