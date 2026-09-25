package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.VirtualThreads
import io.github.matthewjones372.lark.clock
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * One run's workers on a [TestClock], taking turns: one of them runs at a time and the rest are parked,
 * so what a run does at an instant happens in the same order every time.
 *
 * A worker gives up its turn only by [park]ing, until an instant or until something another worker does
 * makes it ready. The run is settled when no worker can go on: each is parked on a later time, or done.
 * The clock stops at the earliest such time on every move, and waits for the run to settle there.
 *
 * Of the workers that can go on, the one forked last goes first. A worker is forked by the stage it feeds,
 * so that is the one furthest upstream, and an element due at an instant reaches a stage before a window
 * of that stage's closes at the same instant.
 */
internal class Turns(private val time: TestClock, private val on: Executor = VirtualThreads) : TestClock.Waiter {

    private class Worker {
        var parked = true
        var done = false
        var until: Instant? = null
        var ready: () -> Boolean = { true }
    }

    private val lock = ReentrantLock()
    private val turned = lock.newCondition()
    private val workers = mutableListOf<Worker>()
    private var turn: Worker? = null

    /** Set when the run is stopped or over: every park returns, and a stage that sees it ends. */
    @Volatile
    var stopped = false
        private set

    fun now(): Instant = time.now()

    /**
     * [body] as a worker of its own, which runs when it is its turn. It reads lark's `clock` as this
     * run's, and a pull built on it finds this run's turns by [here].
     */
    fun fork(body: () -> Unit) {
        val worker = Worker()
        lock.withLock { workers += worker }
        on.execute {
            lock.withLock { while (turn !== worker) turned.await() }
            try {
                current.set(this)
                clock.locally(time, body)
            } finally {
                current.remove()
                lock.withLock {
                    worker.done = true
                    passOn()
                }
            }
        }
    }

    /**
     * Gives up the turn until the clock reaches [until], [ready] answers true, or the run is stopped.
     * [ready] is asked by whichever thread is choosing, and reads only what workers write in their turns.
     */
    fun park(until: Instant? = null, ready: () -> Boolean = { false }) = lock.withLock {
        val me = checkNotNull(turn) { "only the worker whose turn it is parks" }
        me.until = until
        me.ready = ready
        me.parked = true
        passOn()
        while (turn !== me) turned.await()
        me.parked = false
    }

    /** Parks until [delay] from now has passed. */
    fun sleep(delay: Duration) = park(until = now().plusNanos(delay.inWholeNanoseconds))

    /** Stops the run from outside it, and returns once every worker has seen that. */
    fun stop() {
        end()
        settle()
    }

    /** Marks the run over from its own worker, which lets every parked worker go as its turn ends. */
    fun end() {
        stopped = true
    }

    override fun wakesAt(): Instant? =
        lock.withLock { workers.filter { it.parked && !it.done }.mapNotNull { it.until }.minOrNull() }

    override fun settle() = lock.withLock {
        if (turn == null) passOn()
        while (turn != null) turned.await()
    }

    private fun canGoOn(worker: Worker): Boolean =
        !worker.done && worker.parked &&
            (stopped || worker.ready() || worker.until?.let { it <= time.now() } == true)

    private fun passOn() {
        turn = workers.lastOrNull(::canGoOn)
        turned.signalAll()
    }

    companion object {
        private val current = ThreadLocal<Turns>()

        /** The turns of the run whose worker this is. A node with time in it is only pulled on one. */
        fun here(): Turns = checkNotNull(current.get()) { "a node with time in it was pulled off a test's clock" }
    }
}
