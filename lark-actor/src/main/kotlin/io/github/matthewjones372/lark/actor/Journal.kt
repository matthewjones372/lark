package io.github.matthewjones372.lark.actor

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.util.concurrent.ConcurrentHashMap

/** Whose events these are: a kind of entity, and the id of one. */
data class PersistenceId(val kind: String, val id: String)

/** How an event becomes bytes for a journal, and back. No serialisation library is chosen here. */
interface EventCodec<E> {
    fun encode(event: E): ByteArray

    fun decode(bytes: ByteArray): E
}

/** One event as the journal keeps it: its place, counting from 1, and its bytes. */
class StoredEvent(val sequence: Long, val bytes: ByteArray)

/** A writer expected [expected] to be the last sequence number of [id], and it was [actual]: another wrote first. */
data class JournalConflict(val id: PersistenceId, val expected: Long, val actual: Long)

/**
 * Where persistent actors' events are kept, as bytes. An append names the sequence number its writer expects to
 * follow, so that of two writers for one id only one succeeds; a journal that cannot write throws.
 */
interface Journal {
    /** Appends [events] after [expected], 0 for none yet, and answers the new last sequence number. */
    fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long>

    /** The events of [id] from sequence number [from] on, in order. */
    fun read(id: PersistenceId, from: Long = 1): List<StoredEvent>
}

/** The events of [id], decoded by [codec]. */
fun <E> Journal.events(id: PersistenceId, codec: EventCodec<E>): List<E> = read(id).map { codec.decode(it.bytes) }

/** A journal in memory, for tests and for a service that can lose its events. It keeps copies of the bytes. */
class InMemoryJournal : Journal {
    private val kept = ConcurrentHashMap<PersistenceId, List<StoredEvent>>()

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        var conflict: JournalConflict? = null
        val after = kept.compute(id) { _, before ->
            val so = before.orEmpty()
            if (so.size.toLong() != expected) {
                conflict = JournalConflict(id, expected, so.size.toLong())
                so
            } else {
                so + events.mapIndexed { i, bytes -> StoredEvent(expected + i + 1, bytes.copyOf()) }
            }
        }
        return conflict?.left() ?: after.orEmpty().size.toLong().right()
    }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> =
        kept[id].orEmpty().drop((from - 1).coerceAtLeast(0).toInt()).map { StoredEvent(it.sequence, it.bytes.copyOf()) }
}
