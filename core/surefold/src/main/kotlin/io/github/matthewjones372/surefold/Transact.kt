package io.github.matthewjones372.surefold

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A value forks share, changed only inside [transact] (spec 0209). [name] is what a step that lost to a change in it
 * reports as the cause of its retry (spec 0212); keep it to a bounded set, as it becomes a metric tag.
 */
class Shared<T>(initial: T, val name: String? = null) {
    internal val word = Word(Versioned(initial, 0))

    // Threads parked in waitForChange on a step that read this value. Each takes itself off when it wakes.
    internal val waiters = ConcurrentLinkedQueue<Thread>()

    /** The latest committed value, read outside any step: deciding a write on it races every other step. */
    fun peek(): T = word.read().value
}

/**
 * Runs [block] as one step: its writes land together when it commits, or not at all if it throws or fails. A step whose
 * reads were changed by another commit first runs again, so [block] can run more than once. Inside another step, it
 * joins that step. Effects queued with [Transaction.afterCommit] run once it has committed, before this returns.
 */
fun <A> transact(name: String? = null, block: Transaction.() -> A): A {
    running.get()?.let { return it.block() }
    val (step, answer) = untilCommitted(name ?: UNNAMED, block)
    // Outside the step, so an effect that calls transact starts a step of its own; and after the run is counted
    // committed, so an effect that throws does not count it failed.
    step.runEffects()
    return answer
}

// Runs the step until a run commits, recording each run's outcome and each retry's cause (spec 0212).
private fun <A> untilCommitted(name: String, block: Transaction.() -> A): Pair<Transaction, A> {
    var attempts = 0
    try {
        while (true) {
            attempts++
            val step = Transaction()
            val answer = step.attempt(block)
            if (answer !== Again) {
                counter(RUNS, "transact" to name, "outcome" to "committed").increment()
                histogram(ATTEMPTS, "transact" to name).record(attempts.toDouble())
                // Only Again is not the block's answer, so this is an A.
                @Suppress("UNCHECKED_CAST")
                return step to answer as A
            }
            retried.get().retried(name, step.cause)
        }
    } catch (interrupted: InterruptedException) {
        counter(RUNS, "transact" to name, "outcome" to "cancelled").increment()
        throw interrupted
    } catch (t: Throwable) {
        counter(RUNS, "transact" to name, "outcome" to "failed").increment()
        throw t
    }
}

// One run of the step: the block's answer once it has committed, or Again.
private fun <A> Transaction.attempt(block: Transaction.() -> A): Any? {
    running.set(this)
    return try {
        val answer = block()
        if (commit()) answer else Again
    } catch (_: Conflict) {
        Again
    } catch (_: Wait) {
        cause = WAITED
        awaitChange()
        Again
    } finally {
        running.remove()
    }
}

private object Again

private const val UNNAMED = "unnamed"

private const val WAITED = "waited"

/** Told why each run of a step gave up: the step's name, and the value it lost to or `waited` (spec 0212). */
internal fun interface Retried {
    fun retried(step: String, cause: String)
}

internal val retried: SurefoldLocal<Retried> = surefoldLocal {
    Retried { step, cause -> counter(RETRIES, "transact" to step, "cause" to cause).increment() }
}

private const val RUNS = "surefold.transact.runs"

private const val RETRIES = "surefold.transact.retries"

private const val ATTEMPTS = "surefold.transact.attempts"

// A thread local rather than a SurefoldLocal: a fork must not inherit the step it was started in.
private val running = ThreadLocal<Transaction?>()

/** The step a [transact] block runs in. A [Shared] is read and written only through it. */
class Transaction internal constructor() {
    private var readAt: Tick = commits.read()
    private val reads = HashMap<Shared<*>, Versioned<*>>()
    private val writes = HashMap<Shared<*>, Any?>()
    private val effects = ArrayList<() -> Unit>()

    // Why this run gave up: the name of the first value it read that changed, or waited.
    internal var cause: String = UNNAMED

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

    /**
     * Runs [effect] once the step has committed, in the order effects were queued (spec 0211). A run that retries,
     * waits, throws or fails drops its effects, so each runs once per commit. An effect that throws does not undo the
     * commit.
     */
    fun afterCommit(effect: () -> Unit) {
        effects.add(effect)
    }

    /** Drops this run and parks until a value it read is committed again, then runs the step again. */
    fun waitForChange(): Nothing {
        check(reads.isNotEmpty()) { "waitForChange() in a step that has read nothing would wait forever." }
        throw Wait
    }

    /** The first branch that does not wait. When every branch waits, the step waits on what any of them read. */
    fun <A> firstOf(vararg branches: Transaction.() -> A): A {
        for (branch in branches) {
            val before = HashMap(writes)
            val queued = effects.size
            try {
                return branch()
            } catch (_: Wait) {
                writes.clear()
                writes.putAll(before)
                effects.subList(queued, effects.size).clear()
            }
        }
        waitForChange()
    }

    // Every effect runs even if one before it threw: the commit has happened, and each effect is owed to it.
    internal fun runEffects() {
        var thrown: Throwable? = null
        for (effect in effects) {
            try {
                effect()
            } catch (t: Throwable) {
                thrown?.addSuppressed(t) ?: run { thrown = t }
            }
        }
        thrown?.let { throw it }
    }

    // Registered before the check, and a commit wakes after it lands, so a commit is either seen here or wakes us.
    internal fun awaitChange() {
        val me = Thread.currentThread()
        reads.keys.forEach { it.waiters.add(me) }
        try {
            while (!changed()) {
                Waiting.park(this)
                if (Thread.interrupted()) throw InterruptedException()
            }
        } finally {
            reads.keys.forEach { it.waiters.remove(me) }
        }
    }

    private fun changed() = lostTo() != null

    private fun lostTo(): Shared<*>? = reads.entries.firstOrNull { (read, held) -> read.word.read() !== held }?.key

    private fun lose(to: Shared<*>) {
        cause = to.name ?: UNNAMED
    }

    // A value written after readAt is taken only once every earlier read is shown to hold at a later count, so a body
    // never sees values that were never current together.
    private fun <T> consistent(shared: Shared<T>): Versioned<T> {
        while (true) {
            val seen = shared.word.read()
            if (seen.version <= readAt.n) return seen
            val now = commits.read()
            lostTo()?.let { lose(it); throw Conflict }
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
                writes.keys.forEach { shared -> shared.waiters.forEach(Waiting::unpark) }
                return true
            }
            lostTo()?.let { lose(it); return false }
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
