package io.github.matthewjones372.surefold

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/** A value forks share, changed only inside [transact] (spec 0206). */
class Shared<T>(initial: T) {
    internal val word = Word(Versioned(initial, 0))

    // Threads parked in waitForChange on a step that read this value. Each takes itself off when it wakes.
    internal val waiters = ConcurrentLinkedQueue<Thread>()

    /** The latest committed value, read outside any step: deciding a write on it races every other step. */
    fun peek(): T = word.read().value
}

/**
 * Runs [block] as one step: its writes land together when it commits, or not at all if it throws. A step whose reads
 * were changed by another commit first runs again, so [block] can run more than once.
 */
fun <A> transact(block: Transaction.() -> A): A {
    while (true) {
        val step = Transaction()
        try {
            val answer = step.block()
            if (step.commit()) return answer
        } catch (_: Conflict) {
            // Runs again from the start.
        } catch (_: Wait) {
            step.awaitChange()
        }
    }
}

/** The step a [transact] block runs in. A [Shared] is read and written only through it. */
class Transaction internal constructor() {
    private var readAt: Tick = commits.read()
    private val reads = HashMap<Shared<*>, Versioned<*>>()
    private val writes = HashMap<Shared<*>, Any?>()

    fun <T> Shared<T>.get(): T {
        // Only a set on this same Shared put it there, so it is a T.
        @Suppress("UNCHECKED_CAST")
        if (this in writes) return writes[this] as T
        @Suppress("UNCHECKED_CAST")
        val seen = reads.getOrPut(this) { consistent(this) } as Versioned<T>
        return seen.value
    }

    fun <T> Shared<T>.set(value: T) {
        writes[this] = value
    }

    fun <T> Shared<T>.update(change: (T) -> T) = set(change(get()))

    /** Drops this run and parks until a value it read is committed again, then runs the step again. */
    fun waitForChange(): Nothing {
        check(reads.isNotEmpty()) { "waitForChange() in a step that has read nothing would wait forever." }
        throw Wait
    }

    /** The first branch that does not wait. When every branch waits, the step waits on what any of them read. */
    fun <A> firstOf(vararg branches: Transaction.() -> A): A {
        for (branch in branches) {
            val before = HashMap(writes)
            try {
                return branch()
            } catch (_: Wait) {
                writes.clear()
                writes.putAll(before)
            }
        }
        waitForChange()
    }

    // Registered before the check, and a commit wakes after it lands, so a commit is either seen here or wakes us.
    internal fun awaitChange() {
        val me = Thread.currentThread()
        reads.keys.forEach { it.waiters.add(me) }
        try {
            while (!changed()) {
                LockSupport.park(this)
                if (Thread.interrupted()) throw InterruptedException()
            }
        } finally {
            reads.keys.forEach { it.waiters.remove(me) }
        }
    }

    private fun changed() = reads.any { (read, held) -> read.word.read() !== held }

    // A value written after readAt is taken only once every earlier read is shown to hold at a later count, so a body
    // never sees values that were never current together.
    private fun <T> consistent(shared: Shared<T>): Versioned<T> {
        while (true) {
            val seen = shared.word.read()
            if (seen.version <= readAt.n) return seen
            val now = commits.read()
            if (changed()) throw Conflict
            readAt = now
        }
    }

    // The commit count is one of the swapped words, so a commit's writes and its version land together. Words only
    // read are swapped for themselves, which fails the commit if any changed since they were read.
    internal fun commit(): Boolean {
        if (writes.isEmpty()) return true
        while (true) {
            val now = commits.read()
            val next = Tick(now.n + 1)
            val unwritten = reads.filterKeys { it !in writes }.map { (read, held) -> unchanged(read, held) }
            val written = writes.map { (shared, value) -> written(shared, reads[shared], value, next.n) }
            if (swapAll(listOf(Change(commits, now, next)) + unwritten + written)) {
                writes.keys.forEach { shared -> shared.waiters.forEach(LockSupport::unpark) }
                return true
            }
            if (changed()) return false
        }
    }
}

// Values are fresh instances, never equal by identity to one another, which the swap's identity comparison relies on.
internal class Versioned<T>(val value: T, val version: Long)

internal class Tick(val n: Long)

private val commits = Word(Tick(0))

private object Conflict : Throwable(null, null, false, false)

private object Wait : Throwable(null, null, false, false)

@Suppress("UNCHECKED_CAST")
private fun <T> unchanged(shared: Shared<T>, held: Versioned<*>): Change<*> =
    Change(shared.word, held as Versioned<T>, held)

// A write to a Shared the step never read expects whatever is there now.
@Suppress("UNCHECKED_CAST")
private fun <T> written(shared: Shared<T>, read: Versioned<*>?, value: Any?, version: Long): Change<*> =
    Change(shared.word, (read ?: shared.word.read()) as Versioned<T>, Versioned(value as T, version))
