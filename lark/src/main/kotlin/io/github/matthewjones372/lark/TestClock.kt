package io.github.matthewjones372.lark

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * A clock that only moves when a test moves it. A sleep waits for the time it was scheduled at rather
 * than for the time it would have taken.
 */
class TestClock(start: Instant = Instant.EPOCH) : Clock {

    /**
     * Work that waits on this clock by its own means, and has to have done what falls due at an instant
     * before the clock goes past it: a stream on `TestStreams` is one. A move stops at every instant a
     * waiter asks for on the way, and lets it [settle] there before going on.
     */
    interface Waiter {

        /** The earliest instant this is waiting for, or null when nothing it does waits on the time. */
        fun wakesAt(): Instant?

        /** Runs whatever is due by now, and returns once it is waiting on a later time or done. */
        fun settle()
    }

    private val lock = ReentrantLock()
    private val moved = lock.newCondition()
    private val slept = lock.newCondition()

    private var at: Instant = start

    // The time each sleep is waiting for, so a move can tell a sleeper that has not woken yet from one
    // that is genuinely waiting on a time still ahead.
    private val waiting = mutableListOf<Instant>()

    private val waiters = CopyOnWriteArrayList<Waiter>()

    override fun now(): Instant = lock.withLock { at }

    override fun sleep(duration: Duration) = lock.withLock {
        val until = at.plusNanos(duration.inWholeNanoseconds)
        if (until > at) {
            waiting += until
            slept.signalAll()
            try {
                while (at < until) moved.await()
            } finally {
                waiting.remove(until)
                slept.signalAll()
            }
        }
    }

    /** How many sleeps are waiting to be released. */
    fun sleepers(): Int = lock.withLock { waiting.size }

    /**
     * Moves the clock on, stopping at each instant a [Waiter] is waiting for so that what falls due there
     * runs in time order, and returns once every waiter has settled at the new time.
     */
    fun adjust(by: Duration) = advance { it.plusNanos(by.inWholeNanoseconds) }

    fun setTime(to: Instant) = advance { to }

    /**
     * Waits until something is asleep and then moves, because nothing here knows a fork has reached its
     * sleep the way a fiber runtime does: a plain [adjust] races the sleeper it means to release.
     */
    fun adjustWhenBlocked(by: Duration) {
        lock.withLock { while (waiting.isEmpty() || waiting.any { it <= at }) slept.await() }
        adjust(by)
    }

    /** [waiter] is stopped for by every move until the returned handle is closed. */
    fun register(waiter: Waiter): AutoCloseable {
        waiters += waiter
        return AutoCloseable { waiters -= waiter }
    }

    // A waiter is asked and settled without the lock held: settling runs work that reads the time.
    private fun advance(target: (Instant) -> Instant) {
        val to = lock.withLock { target(at) }
        generateSequence { waiters.mapNotNull { it.wakesAt() }.filter { it <= to }.minOrNull() }
            .forEach { due ->
                lock.withLock { if (due > at) moveTo(due) }
                waiters.forEach { it.settle() }
            }
        lock.withLock { moveTo(to) }
        waiters.forEach { it.settle() }
    }

    private fun moveTo(to: Instant) {
        at = to
        moved.signalAll()
    }
}
