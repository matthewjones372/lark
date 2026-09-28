package io.github.matthewjones372.lark.actor.journal.jdbc

import arrow.core.Either
import io.github.matthewjones372.lark.actor.JournalConflict
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.actor.SliceElsewhere
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * Appends that share a commit (spec 0108): up to [maxAppends] of them in one transaction, and up to [committers]
 * such transactions at once, each on its own connection.
 */
data class GroupCommit(val maxAppends: Int = 64, val committers: Int = 2) {
    init {
        require(maxAppends >= 1) { "a group holds at least one append, was $maxAppends" }
        require(committers >= 1) { "at least one committer, was $committers" }
    }
}

/**
 * Takes whatever appends are queued, runs them in one transaction and commits once. There is no timer: a lone append
 * goes at once, and appends that arrive while a commit is in flight form the next group. A committer ends when it
 * finds nothing queued, so an idle journal holds no thread.
 */
internal class Committer(
    private val dataSource: DataSource,
    private val settings: GroupCommit,
    private val appendWithin: (Connection, PersistenceId, Long, List<ByteArray>) -> Either<JournalConflict, Long>,
) {
    private class Pending(val id: PersistenceId, val expected: Long, val events: List<ByteArray>) {
        val answer = CompletableFuture<Either<JournalConflict, Long>>()
    }

    private val queue = ConcurrentLinkedQueue<Pending>()
    private val running = AtomicInteger()

    /** How many appends wait for a committer, for tests. */
    fun queued(): Int = queue.size

    fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        val pending = Pending(id, expected, events)
        queue.add(pending)
        if (claim()) Thread.ofVirtual().name("lark-journal-committer").start(::run)
        return try {
            pending.answer.join()
        } catch (failed: CompletionException) {
            throw failed.cause ?: failed
        }
    }

    /** A committer's place, if fewer than [GroupCommit.committers] are running. */
    private fun claim(): Boolean {
        while (true) {
            val now = running.get()
            if (now >= settings.committers) return false
            if (running.compareAndSet(now, now + 1)) return true
        }
    }

    private fun run() {
        while (true) {
            val group = generateSequence { queue.poll() }.take(settings.maxAppends).toList()
            if (group.isEmpty()) {
                running.decrementAndGet()
                // An append queued between the empty poll and the decrement found every place taken: it is ours.
                if (queue.isEmpty() || !claim()) return
            } else {
                commit(group)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Whatever failed is every waiting append's to throw, never lost here.
    private fun commit(group: List<Pending>) {
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                val results = group.map { pending ->
                    // Each append's own failure, a fenced slice or an error, is rolled back to its savepoint alone.
                    pending to try {
                        Result.success(appendWithin(connection, pending.id, pending.expected, pending.events))
                    } catch (refused: SQLException) {
                        Result.failure(refused)
                    } catch (elsewhere: SliceElsewhere) {
                        Result.failure(elsewhere)
                    }
                }
                try {
                    connection.commit()
                } catch (failed: SQLException) {
                    try {
                        connection.rollback()
                    } catch (_: SQLException) {
                        // The commit's failure is the one to report; the connection goes back to the pool broken.
                    }
                    throw failed
                }
                results.forEach { (pending, result) ->
                    result.fold({ pending.answer.complete(it) }, { pending.answer.completeExceptionally(it) })
                }
            }
        } catch (failed: Throwable) {
            // No append is answered as written unless its group committed.
            group.forEach { it.answer.completeExceptionally(failed) }
        }
    }
}
