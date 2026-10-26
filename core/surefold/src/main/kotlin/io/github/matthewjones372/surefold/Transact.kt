package io.github.matthewjones372.surefold

/** A value forks share, changed only inside [transact] (spec 0206). */
class Shared<T>(initial: T) {
    internal val word = Word(Versioned(initial, 0))

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
        val answer = try {
            step.block()
        } catch (_: Conflict) {
            continue
        }
        if (step.commit()) return answer
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

    // A value written after readAt is taken only once every earlier read is shown to hold at a later count, so a body
    // never sees values that were never current together.
    private fun <T> consistent(shared: Shared<T>): Versioned<T> {
        while (true) {
            val seen = shared.word.read()
            if (seen.version <= readAt.n) return seen
            val now = commits.read()
            if (reads.any { (read, held) -> read.word.read() !== held }) throw Conflict
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
            if (swapAll(listOf(Change(commits, now, next)) + unwritten + written)) return true
            if (reads.any { (read, held) -> read.word.read() !== held }) return false
        }
    }
}

// Values are fresh instances, never equal by identity to one another, which the swap's identity comparison relies on.
internal class Versioned<T>(val value: T, val version: Long)

internal class Tick(val n: Long)

private val commits = Word(Tick(0))

private object Conflict : Throwable(null, null, false, false)

@Suppress("UNCHECKED_CAST")
private fun <T> unchanged(shared: Shared<T>, held: Versioned<*>): Change<*> =
    Change(shared.word, held as Versioned<T>, held)

// A write to a Shared the step never read expects whatever is there now.
@Suppress("UNCHECKED_CAST")
private fun <T> written(shared: Shared<T>, read: Versioned<*>?, value: Any?, version: Long): Change<*> =
    Change(shared.word, (read ?: shared.word.read()) as Versioned<T>, Versioned(value as T, version))
