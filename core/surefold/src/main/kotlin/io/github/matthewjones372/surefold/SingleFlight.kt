package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.fail.Either
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * One call per key while it is in flight (spec 0247): the first [run] for a key starts its block, and every [run] for
 * that key until it ends waits on it and is answered the same. Nothing is remembered once it ends; remembering is a
 * cache's. The block runs on a virtual thread of its own with the first caller's bindings, so a caller that stops
 * waiting stops only itself, and the block still answers the rest.
 */
class SingleFlight<K : Any, E, V>(private val name: String = "single-flight") {
    private val flights = ConcurrentHashMap<K, Promise<Throwable, Either<E, V>>>()
    private val running = ConcurrentHashMap<K, Thread>()
    private val cancels = AtomicLong()

    /** [block]'s answer for [key], from this call's run of it or the one in flight. A throw reaches every waiter. */
    fun run(key: K, block: () -> Either<E, V>): Either<E, V> {
        val mine = Promise<Throwable, Either<E, V>>(name)
        val flight = flights.putIfAbsent(key, mine) ?: mine.also { fly(key, it, block) }
        return when (val ended = flight.await()) {
            is Either.Right -> ended.value
            is Either.Left -> throw ended.value
        }
    }

    /** How many keys have a call in flight now. */
    fun inFlight(): Int = flights.size

    /**
     * Interrupts every block in flight, for an owner that is closing: each ends at its next interruptible call, and its
     * waiters are answered with what it throws then.
     */
    fun cancelAll() {
        cancels.incrementAndGet()
        running.values.forEach { it.interrupt() }
    }

    /** How many callers are parked on [key]'s flight now: none once it has ended, or before one starts. */
    fun waiting(key: K): Int = flights[key]?.waiting() ?: 0

    @Suppress("TooGenericExceptionCaught") // whatever the block throws is every waiter's to see, not this class's
    private fun fly(key: K, flight: Promise<Throwable, Either<E, V>>, block: () -> Either<E, V>) {
        val asked = cancels.get()
        surefoldFuture<Unit> {
            // Registered by the block's own thread before it runs, and the count read after: a cancelAll either finds
            // this thread to interrupt or has already counted, and is seen here. Neither misses a block just started.
            running[key] = Thread.currentThread()
            if (cancels.get() != asked) Thread.currentThread().interrupt()
            val ended: Either<Throwable, Either<E, V>> = try {
                Either.Right(block())
            } catch (thrown: Throwable) {
                Either.Left(thrown)
            }
            // Free the key first, so a call that sees this answer and comes back runs the block again.
            running.remove(key)
            // A cancel that arrived as the block ended is not the answer's to carry.
            Thread.interrupted()
            flights.remove(key, flight)
            when (ended) {
                is Either.Right -> flight.succeed(ended.value)
                is Either.Left -> flight.fail(ended.value)
            }
        }
    }
}
