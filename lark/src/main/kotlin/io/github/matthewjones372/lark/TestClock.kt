package io.github.matthewjones372.lark

import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * A clock that only moves when a test moves it. A sleep waits for the time it was scheduled at rather
 * than for the time it would have taken.
 */
class TestClock(start: Instant = Instant.EPOCH) : Clock {

    private val lock = ReentrantLock()
    private val moved = lock.newCondition()
    private val slept = lock.newCondition()

    private var at: Instant = start

    // The time each sleep is waiting for, so a move can tell a sleeper that has not woken yet from one
    // that is genuinely waiting on a time still ahead.
    private val waiting = mutableListOf<Instant>()

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

    fun adjust(by: Duration) = lock.withLock { moveTo(at.plusNanos(by.inWholeNanoseconds)) }

    fun setTime(to: Instant) = lock.withLock { moveTo(to) }

    /**
     * Waits until something is asleep and then moves, because nothing here knows a fork has reached its
     * sleep the way a fiber runtime does: a plain [adjust] races the sleeper it means to release.
     */
    fun adjustWhenBlocked(by: Duration) = lock.withLock {
        while (waiting.isEmpty() || waiting.any { it <= at }) slept.await()
        moveTo(at.plusNanos(by.inWholeNanoseconds))
    }

    private fun moveTo(to: Instant) {
        at = to
        moved.signalAll()
    }
}
