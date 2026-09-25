package io.github.matthewjones372.lark.actor

import java.util.concurrent.atomic.AtomicReference

/**
 * Many senders, one reader: a send is a single swap that never retries, where a lock-free queue's CAS on its tail
 * retries whenever two senders meet. Only the actor's own activation reads. The same queue Pekko's mailbox uses.
 */
internal class Mailbox<M : Any> {

    private class Node<M : Any>(@Volatile var message: M?) {
        @Volatile var next: Node<M>? = null
    }

    private val tail = AtomicReference(Node<M>(null))

    // Read and written only by the activation that holds the actor.
    @Volatile private var head: Node<M> = tail.get()

    fun add(message: M) {
        val node = Node(message)
        // Between the swap and the link the queue reads as empty, so a sender schedules the actor only after linking.
        tail.getAndSet(node).next = node
    }

    fun poll(): M? {
        val next = head.next ?: return null
        head = next
        return next.message.also { next.message = null }
    }

    fun isNotEmpty(): Boolean = head.next != null
}
