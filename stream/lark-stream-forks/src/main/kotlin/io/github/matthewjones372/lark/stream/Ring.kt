package io.github.matthewjones372.lark.stream

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.locks.LockSupport

// Padding, so the producer's index and the consumer's never share a cache line: each side writes its own
// on every element, and a shared line would bounce between their cores on every one of them.
@Suppress("unused")
internal open class RingPadBeforeTail {
    @JvmField var p01 = 0L

    @JvmField var p02 = 0L

    @JvmField var p03 = 0L

    @JvmField var p04 = 0L

    @JvmField var p05 = 0L

    @JvmField var p06 = 0L

    @JvmField var p07 = 0L
}

internal open class RingTail : RingPadBeforeTail() {
    @Volatile @JvmField
    var tail = 0L
}

@Suppress("unused")
internal open class RingPadBeforeHead : RingTail() {
    @JvmField var p11 = 0L

    @JvmField var p12 = 0L

    @JvmField var p13 = 0L

    @JvmField var p14 = 0L

    @JvmField var p15 = 0L

    @JvmField var p16 = 0L

    @JvmField var p17 = 0L
}

internal open class RingHead : RingPadBeforeHead() {
    @Volatile @JvmField
    var head = 0L
}

@Suppress("unused")
internal open class RingPadAfterHead : RingHead() {
    @JvmField var p21 = 0L

    @JvmField var p22 = 0L

    @JvmField var p23 = 0L

    @JvmField var p24 = 0L

    @JvmField var p25 = 0L

    @JvmField var p26 = 0L

    @JvmField var p27 = 0L
}

/**
 * A ring of [capacity] between one producer and one consumer (spec 0169), which wakes either side only when it
 * must: the consumer when the ring stops being empty, the producer once a batch of room has been made.
 *
 * Either side parks the same way: it names itself, fences, and looks once more before parking, so a wake can
 * only be missed if the other side's fence is missing too. The producer fences on every put, since one
 * element may be all the consumer gets for a long time. The consumer fences only where its index crosses a
 * multiple of the batch: a parked producer left the ring full, so the consumer crosses one before it empties.
 */
internal class Ring(private val capacity: Int) : RingPadAfterHead() {
    init {
        require(capacity >= 1) { "a ring needs room for at least one element, not $capacity" }
    }

    private val slots = arrayOfNulls<Any>(capacity)
    private val batchMask = (Integer.highestOneBit(maxOf(1, capacity / BATCHES))).toLong() - 1

    // Each side's last sight of the other's index, re-read only when it seems to have run out.
    private var headSeen = 0L
    private var tailSeen = 0L

    @Volatile private var parkedProducer: Thread? = null

    @Volatile private var parkedConsumer: Thread? = null

    private fun slot(index: Long) = (index % capacity).toInt()

    /** Waits while the ring is full. Throws [InterruptedException] if interrupted, whether it had to wait or not. */
    fun put(element: Any) {
        if (Thread.interrupted()) throw InterruptedException()
        val t = tail
        while (t - headSeen >= capacity && t - head.also { headSeen = it } >= capacity) parkUntilRoom(t)
        slots[slot(t)] = element
        TAIL.setRelease(this, t + 1)
        VarHandle.fullFence()
        parkedConsumer?.let { consumer ->
            parkedConsumer = null
            LockSupport.unpark(consumer)
        }
    }

    /** Waits while the ring is empty. Throws [InterruptedException] if interrupted, whether it had to wait or not. */
    fun take(): Any {
        if (Thread.interrupted()) throw InterruptedException()
        var yielded = 0
        while (true) {
            poll()?.let { return it }
            // A yield lets a producer on the same carrier run, and is far cheaper than a park and an unpark.
            if (yielded++ < YIELDS_BEFORE_PARKING) Thread.yield() else parkUntilNotEmpty()
        }
    }

    private fun parkUntilRoom(t: Long) {
        parkedProducer = Thread.currentThread()
        VarHandle.fullFence()
        if (t - head >= capacity) LockSupport.park(this)
        parkedProducer = null
        if (Thread.interrupted()) throw InterruptedException()
    }

    private fun parkUntilNotEmpty() {
        parkedConsumer = Thread.currentThread()
        VarHandle.fullFence()
        if (head >= tail) LockSupport.park(this)
        parkedConsumer = null
        if (Thread.interrupted()) throw InterruptedException()
    }

    /** The next element, or `null` at once if the ring is empty. */
    fun poll(): Any? {
        val h = head
        if (h >= tailSeen && h >= tail.also { tailSeen = it }) return null
        val i = slot(h)
        val element = slots[i]
        slots[i] = null
        HEAD.setRelease(this, h + 1)
        if ((h + 1) and batchMask == 0L) {
            VarHandle.fullFence()
            parkedProducer?.let { producer ->
                parkedProducer = null
                LockSupport.unpark(producer)
            }
        }
        return element
    }

    private companion object {
        const val BATCHES = 4
        const val YIELDS_BEFORE_PARKING = 4

        val TAIL: VarHandle =
            MethodHandles.lookup().findVarHandle(RingTail::class.java, "tail", Long::class.javaPrimitiveType)
        val HEAD: VarHandle =
            MethodHandles.lookup().findVarHandle(RingHead::class.java, "head", Long::class.javaPrimitiveType)
    }
}
