package io.github.matthewjones372.lark.actor

import java.lang.Thread.State.BLOCKED
import java.lang.Thread.State.TIMED_WAITING
import java.lang.Thread.State.WAITING
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Something a runner can run: an actor's activation. */
internal fun interface Activation {
    fun activate()
}

/** How long a runner with nothing to take spins before it leaves: a wake that close behind starts no thread. */
private val SPIN_NANOS = TimeUnit.MICROSECONDS.toNanos(10)

/** How often the watcher looks at the runners while work is queued. */
private val WATCH_NANOS = TimeUnit.MICROSECONDS.toNanos(50)

/**
 * The virtual threads that run one flock's actors. A woken actor joins a queue, and [parallelism] runners, one per
 * carrier, take actors from it in turn, so a burst of wakes starts a handful of threads rather than one each. A
 * runner that finds nothing spins briefly and then leaves, so nothing ever has to wake it. A step that blocks parks
 * its runner; when every runner is in a step and the queue stands still, a watcher adds runners, doubling each time
 * it still stands still, up to [cap].
 */
internal class Runners(private val parallelism: Int, private val cap: Int) {
    /** One runner, and whether it has an activation in hand; only the runner itself writes it. */
    private class Runner {
        lateinit var thread: Thread

        @Volatile
        var busy = false

        /** In a step that is parked on something: a lock, a sleep, a read. */
        fun blocked(): Boolean = busy && thread.state.let { it == WAITING || it == TIMED_WAITING || it == BLOCKED }
    }

    private val ready = ConcurrentLinkedQueue<Activation>()

    // Activations taken before any in [ready]: the few actors every node's health depends on, such as the cluster's
    // own, which must not wait behind thousands of busy entities for their turn (spec 0104).
    private val urgent = ConcurrentLinkedQueue<Activation>()

    private fun nothingQueued() = urgent.isEmpty() && ready.isEmpty()
    private val active = AtomicInteger()
    private val watching = AtomicBoolean()
    private val started = ConcurrentHashMap.newKeySet<Runner>()
    private val lock = ReentrantLock()
    private val quiet = lock.newCondition()

    // How many threads wait in awaitIdle, read by a runner each time it finds the queue empty.
    @Volatile
    private var awaiting = 0

    /**
     * Waits until nothing is queued and no runner holds an activation. The queue is read before the runners: an
     * activation not yet taken is still queued, and one taken belongs to a runner that marked itself busy first.
     */
    fun awaitIdle() = lock.withLock {
        awaiting++
        try {
            while (!nothingQueued() || started.any { it.busy }) quiet.await()
        } finally {
            awaiting--
        }
    }

    fun submit(activation: Activation, first: Boolean = false) {
        if (first) urgent.offer(activation) else ready.offer(activation)
        if (!grow(parallelism)) watch()
    }

    /** Starts a runner when fewer than [limit] run; whether it did. */
    private fun grow(limit: Int): Boolean {
        while (true) {
            val running = active.get()
            if (running >= limit) return false
            if (active.compareAndSet(running, running + 1)) {
                val runner = Runner()
                runner.thread = Thread.ofVirtual().unstarted { run(runner) }
                started.add(runner)
                runner.thread.start()
                return true
            }
        }
    }

    private tailrec fun run(runner: Runner) {
        runner.busy = true
        val next = urgent.poll() ?: ready.poll()
        if (next != null) {
            runOne(next)
            return run(runner)
        }
        runner.busy = false
        if (awaiting > 0) lock.withLock { quiet.signalAll() }
        if (workArrives()) return run(runner)
        started.remove(runner)
        active.decrementAndGet()
        // An actor queued after the last poll, by a wake that counted this runner as still running, is ours to take.
        if (nothingQueued() || !rejoin()) return
        started.add(runner)
        run(runner)
    }

    /** Spins for up to [SPIN_NANOS] watching for work, without taking it: whether some arrived. */
    private fun workArrives(): Boolean {
        val deadline = System.nanoTime() + SPIN_NANOS
        while (System.nanoTime() < deadline) {
            if (!nothingQueued()) return true
            Thread.onSpinWait()
        }
        return false
    }

    private fun rejoin(): Boolean {
        while (true) {
            val running = active.get()
            if (running >= cap) return false
            if (active.compareAndSet(running, running + 1)) return true
        }
    }

    /** Starts the watcher, if it is not already watching. */
    private fun watch() {
        if (!watching.get() && watching.compareAndSet(false, true)) Thread.ofVirtual().start(::watcher)
    }

    /**
     * While work is queued, keeps [parallelism] runners able to run: those parked in a blocking step do not count,
     * and the shortfall is made up with new runners, doubling each tick it lasts, so a burst of blocking actors is
     * each on a runner of its own within a few ticks. Leaves once the queue is empty.
     */
    private fun watcher() {
        var adding = 0
        while (true) {
            LockSupport.parkNanos(WATCH_NANOS)
            if (nothingQueued()) {
                watching.set(false)
                // Work queued after the check, by a wake that saw the watcher still watching, is still ours.
                if (nothingQueued() || !watching.compareAndSet(false, true)) return
            }
            val runnable = active.get() - started.count { it.blocked() }
            if (runnable < parallelism) {
                adding = maxOf(parallelism - runnable, adding * 2).coerceAtMost(cap)
                repeat(adding) { grow(cap) }
            } else {
                adding = 0
            }
        }
    }

    // An actor's throw ends that actor and goes to the thread's handler, as it did on a thread of its own; the
    // runner carries on with the next.
    @Suppress("TooGenericExceptionCaught")
    private fun runOne(activation: Activation) {
        try {
            activation.activate()
        } catch (fatal: VirtualMachineError) {
            active.decrementAndGet()
            throw fatal
        } catch (thrown: Throwable) {
            val thread = Thread.currentThread()
            thread.uncaughtExceptionHandler.uncaughtException(thread, thrown)
        }
    }
}
