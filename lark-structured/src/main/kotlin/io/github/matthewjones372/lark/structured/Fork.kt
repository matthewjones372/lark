package io.github.matthewjones372.lark.structured

import io.github.matthewjones372.lark.Deferred
import io.github.matthewjones372.lark.Flock
import java.util.concurrent.CountDownLatch

/**
 * One subtask of a structured scope. The JDK has no lazy subtask and no way to cancel one, so both are
 * here; its thread is its own, so an interrupt sent late lands on nothing else.
 */
internal class Fork<E, T>(
    private val scope: Scope<E>,
    val name: String,
    private val block: Flock<E>.() -> T,
) : Deferred<T> {

    // Set by the scope's thread factory, on the owner's thread, while `start` is forking.
    var thread: Thread? = null

    private val ended = CountDownLatch(1)

    @Volatile
    private var outcome: Outcome<E, T>? = null

    // The owner's alone, like the rest of the handle.
    private var started = false
    private var noticed = false
    private var cutShort = false

    fun run() {
        outcome = capture(name, block)
        ended.countDown()
    }

    fun start() {
        if (started) return
        started = true
        if (!scope.submit(this)) settle(Thrown(InterruptedException("'$name' never started: its scope was cancelled")))
    }

    override fun await(): T {
        scope.checkOwner()
        start()
        ended.awaitFully()
        noticed = true
        return when (val settled = checkNotNull(outcome) { "an ended fork has an outcome" }) {
            is Returned -> settled.value

            is Failure -> {
                if (settled.isInterrupt() && scope.hasExpired()) scope.expire()
                scope.surface(settled)
            }
        }
    }

    override fun cancel() {
        scope.checkOwner()
        noticed = true
        if (!started) {
            started = true
            settle(Thrown(InterruptedException("'$name' was cancelled before it started")))
            return
        }
        thread?.interrupt()
        ended.awaitFully()
    }

    fun hasEnded(): Boolean = ended.count == 0L

    fun markCutShort() {
        cutShort = true
    }

    /** The failure of a fork that ran, that nobody asked about, and that the close did not cut short. */
    fun unnoticedFailure(): Failure<E>? =
        if (!started || noticed || cutShort) null else outcome as? Failure<E>

    private fun settle(settled: Outcome<E, T>) {
        outcome = settled
        ended.countDown()
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
