package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Schedule
import kotlin.time.Duration

/** What an entities manager is told: a message for one entity, or its own bookkeeping. */
sealed interface Entities<M : Any>

/** [message] for the entity [id]. */
internal class Deliver<M : Any>(val id: String, val message: M) : Entities<M>

/** The entity [id] has had nothing for its `passivateAfter`. */
internal class Passivate<M : Any>(val id: String) : Entities<M>

/** The key of the timer that passivates [id]. */
private data class IdleKey(val id: String)

/**
 * One manager's bookkeeping: the entity running for each id, the id of each running entity, and what has arrived for
 * each id whose entity is stopping. Made on the manager's first message, so a restart, and a second spawn of the same
 * behaviour, start with none.
 */
private class EntityBook<M : Any, S, E>(
    private val passivateAfter: Duration,
    private val restart: Schedule<Failure<E>, *>?,
    private val entity: (id: String) -> Behaviour<M, S, E>,
) {
    private val running = HashMap<String, ActorRef<M>>()
    private val ids = HashMap<ActorRef<*>, String>()
    private val stopping = HashMap<String, MutableList<M>>()

    /** Tells the entity [id] [message], starting it if it is not running, and keeping it if it is stopping. */
    fun deliver(ctx: Ctx<Entities<M>>, id: String, message: M) {
        val held = stopping[id]
        if (held != null) {
            held += message
            return
        }
        (running[id] ?: start(ctx, id)).tell(message)
        ctx.timers.after(IdleKey(id), passivateAfter, Passivate(id))
    }

    fun passivate(ctx: Ctx<Entities<M>>, id: String) {
        val ref = running.remove(id) ?: return
        stopping[id] = mutableListOf()
        ctx.stop(ref)
    }

    /** An entity has stopped, by passivation or by itself; what arrived for it meanwhile starts it again. */
    fun ended(ctx: Ctx<Entities<M>>, ref: ActorRef<*>) {
        val id = ids.remove(ref) ?: return
        if (running[id] == ref) {
            running.remove(id)
            ctx.timers.cancel(IdleKey(id))
        }
        stopping.remove(id)?.forEach { deliver(ctx, id, it) }
    }

    private fun start(ctx: Ctx<Entities<M>>, id: String): ActorRef<M> {
        val ref = ctx.spawn(id, entity(id), restart)
        ctx.watch(ref)
        running[id] = ref
        ids[ref] = id
        return ref
    }
}

/**
 * A manager of entities: one actor per id, spawned by [entity] as the manager's child on the id's first message, and
 * stopped once it has had nothing for [passivateAfter]. A message for an entity that is stopping is kept, and starts
 * it again once it has stopped. Talk to one through [entity], whose ref stays good while the entity comes and goes.
 * [restart] applies to each entity on its own.
 */
fun <M : Any, S, E> entities(
    passivateAfter: Duration,
    restart: Schedule<Failure<E>, *>? = null,
    entity: (id: String) -> Behaviour<M, S, E>,
): Behaviour<Entities<M>, Any?, Nothing> {
    require(passivateAfter.isPositive()) { "passivateAfter must be positive, was $passivateAfter" }
    return behaviour<Entities<M>, Any?>(null) { ctx, state, message ->
        @Suppress("UNCHECKED_CAST")
        val book = state as EntityBook<M, S, E>? ?: EntityBook(passivateAfter, restart, entity)
        when (message) {
            is Deliver -> book.deliver(ctx, message.id, message.message)
            is Passivate -> book.passivate(ctx, message.id)
        }
        if (state == null) become(book) else stay()
    }.onSignal { ctx, state, signal ->
        @Suppress("UNCHECKED_CAST")
        val book = state as EntityBook<M, S, E>?
        if (signal is Signal.Terminated && book != null) book.ended(ctx, signal.ref)
        stay()
    }
}

/** The entity [id] of these entities, as a ref of its own protocol: good whether or not it is running now. */
fun <M : Any> ActorRef<Entities<M>>.entity(id: String): ActorRef<M> = EntityRef(this, id)

private class EntityRef<M : Any>(private val manager: ActorRef<Entities<M>>, private val id: String) : ActorRef<M> {
    override val address = Address(manager.address.node, "${manager.address.path}/$id", manager.address.incarnation)

    override fun tell(message: M) = manager.tell(Deliver(id, message))

    override fun equals(other: Any?): Boolean = other is EntityRef<*> && other.manager == manager && other.id == id

    override fun hashCode(): Int = manager.hashCode() * 31 + id.hashCode()

    override fun toString(): String = "EntityRef(${address.path})"
}
