package io.github.matthewjones372.lark.structured

import io.github.matthewjones372.lark.Deferred
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch

/**
 * One subtask of a structured scope, forked only when something awaits it. The JDK has no lazy subtask,
 * so the laziness is here; its thread is its own, so nothing it does can land on another task.
 */
internal class Fork<E, T>(
    private val scope: Scope<E>,
    val name: String,
    private val block: StructuredScope<E>.() -> T,
) : Deferred<T> {

    // Set by the scope's thread factory, on the owner's thread, while `start` is forking.
    var thread: Thread? = null

    private val ended = CountDownLatch(1)
    private val waiting = ConcurrentLinkedQueue<() -> Unit>()

    @Volatile
    private var outcome: Outcome<E, T>? = null

    // The owner's alone, like the rest of the handle.
    private var started = false

    fun run() = settle(capture(name, block))

    fun start() {
        if (started) return
        started = true
        if (!scope.submit(this)) settle(Thrown(InterruptedException("'$name' never started: its scope was cancelled")))
    }

    override fun await(): T {
        scope.checkOwner()
        start()
        ended.awaitFully()
        return when (val settled = checkNotNull(outcome) { "an ended fork has an outcome" }) {
            is Returned -> settled.value

            is Failure -> {
                if (settled.isInterrupt() && scope.hasExpired()) scope.expire()
                scope.surface(settled)
            }
        }
    }

    /**
     * Makes sure this fork never runs. Only a fork nobody has awaited can be unstarted, and one that was
     * awaited has ended by the time the block runs again, so there is never a thread to interrupt.
     */
    override fun cancel() {
        scope.checkOwner()
        if (started) return
        started = true
        settle(Thrown(InterruptedException("'$name' was cancelled before it started")))
    }

    fun belongsTo(other: Scope<*>): Boolean = scope === other

    fun hasFailed(): Boolean = outcome is Failure

    /** Runs [action] once this fork has ended, on whichever thread ends it, and at once if it already has. */
    fun whenEnded(action: () -> Unit) {
        waiting += action
        // Whoever removes it runs it: this thread if the fork ended first, the fork's thread otherwise.
        if (ended.count == 0L && waiting.remove(action)) action()
    }

    private fun settle(settled: Outcome<E, T>) {
        outcome = settled
        ended.countDown()
        while (true) (waiting.poll() ?: break)()
    }
}

/** An interrupt landing on the waiting owner must not leave it short of the fork's answer. */
private fun CountDownLatch.awaitFully() {
    var interrupted = false
    while (count > 0L) {
        try {
            await()
        } catch (stop: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}
