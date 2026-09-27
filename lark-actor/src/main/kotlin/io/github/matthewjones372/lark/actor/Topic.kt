package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.increment

/** What a topic's actor is told (spec 0082). */
sealed interface TopicMessage<M : Any> {
    /** [message] published here: to this node's subscribers, and on to the other nodes. */
    data class Publish<M : Any>(val message: M) : TopicMessage<M>

    /** [message] published on another node: to this node's subscribers only, and no further. */
    data class Arrive<M : Any>(val message: M) : TopicMessage<M>

    data class Subscribe<M : Any>(val subscriber: ActorRef<M>) : TopicMessage<M>

    data class Unsubscribe<M : Any>(val subscriber: ActorRef<M>) : TopicMessage<M>
}

/**
 * A topic on this node: every message published to it reaches every subscriber, at most once, in the order one
 * publisher published them. A subscriber that stops is dropped; one that cannot be watched, such as a sharded entity
 * or a singleton, hears until it unsubscribes. A cluster's topic reaches the subscribers of every `Up` member
 * (spec 0082).
 */
class Topic<M : Any> internal constructor(val name: String, val ref: ActorRef<TopicMessage<M>>) {
    /** [subscriber] hears every message published from now on, until it stops or [unsubscribe]. */
    fun subscribe(subscriber: ActorRef<M>) = ref.tell(TopicMessage.Subscribe(subscriber))

    fun unsubscribe(subscriber: ActorRef<M>) = ref.tell(TopicMessage.Unsubscribe(subscriber))

    /** Tells [message] to every subscriber, a publisher that subscribed included. */
    fun publish(message: M) = ref.tell(TopicMessage.Publish(message))
}

/**
 * The topic [name] on this flock, its actor at `/user/topic-<name>`. Each publish here is also handed to [forward],
 * which is how a cluster's topic reaches the other members; a message from them arrives as [TopicMessage.Arrive].
 * It records `lark.topic.published`, `lark.topic.delivered` and `lark.topic.subscribers` (spec 0081).
 */
fun <M : Any> Flock<*>.topic(name: String, forward: (M) -> Unit = {}): Topic<M> {
    val published = counter("lark.topic.published", "topic" to name)
    val delivered = counter("lark.topic.delivered", "topic" to name)
    val subscribers = gauge("lark.topic.subscribers", "topic" to name)
    fun Set<ActorRef<M>>.hear(message: M) = forEach {
        it.tell(message)
        delivered.increment()
    }

    fun Set<ActorRef<M>>.counted() = also { subscribers.set(size.toDouble()) }
    val actor = behaviour<TopicMessage<M>, Set<ActorRef<M>>>(emptySet()) { ctx, heard, message ->
        when (message) {
            is TopicMessage.Publish -> {
                published.increment()
                heard.hear(message.message)
                forward(message.message)
                stay()
            }

            is TopicMessage.Arrive -> stay().also { heard.hear(message.message) }

            is TopicMessage.Subscribe -> {
                // One that cannot be watched stays until it unsubscribes.
                if (message.subscriber.canBeWatched()) ctx.watch(message.subscriber)
                become((heard + message.subscriber).counted())
            }

            is TopicMessage.Unsubscribe -> become((heard - message.subscriber).counted())
        }
    }.onSignal { _, heard, signal ->
        if (signal is Signal.Terminated) {
            become(heard.filterTo(mutableSetOf()) { it != signal.ref }.counted())
        } else {
            stay()
        }
    }
    return Topic(name, spawn("topic-$name", actor))
}
