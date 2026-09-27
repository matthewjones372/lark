package io.github.matthewjones372.lark.actor

import arrow.core.Either

/**
 * Which of [COUNT] slices an id's events live in (spec 0088): murmur3 of `"kind|id"`. The count and the hash are fixed
 * forever, since changing either would move ids away from the events they already wrote.
 */
object Slices {
    const val COUNT: Int = 1024

    fun of(id: PersistenceId): Int = murmur3("${id.kind}|${id.id}".toByteArray(Charsets.UTF_8)) and (COUNT - 1)
}

/** Named databases, each owning a contiguous range of slices in the order given. */
internal class Routing<out T>(databases: List<Pair<String, T>>) {
    val named: List<Pair<String, T>> = databases.toList()

    init {
        require(named.isNotEmpty()) { "sharded over no databases" }
        require(named.size <= Slices.COUNT) { "${named.size} databases, but only ${Slices.COUNT} slices" }
        val repeated = named.groupBy { it.first }.filterValues { it.size > 1 }.keys
        require(repeated.isEmpty()) { "databases named twice: $repeated" }
    }

    fun of(id: PersistenceId): Pair<String, T> = named[Slices.of(id) * named.size / Slices.COUNT]
}

/**
 * A journal split across databases by id (spec 0088): each id's events, and each append's optimistic check, stay in
 * the one database its slice belongs to. There is no order across databases; each has its own feed in [feeds].
 */
class ShardedJournal(databases: List<Pair<String, Journal>>) :
    Journal,
    JournalPruning {
    private val routing = Routing(databases)

    /** Each database's own feed, by name, for one projection per database. */
    val feeds: List<Pair<String, JournalFeed>> by lazy {
        routing.named.map { (name, journal) ->
            name to requireNotNull(journal as? JournalFeed) { "the journal of $name has no feed" }
        }
    }

    /** The name of the database [id]'s events live in. */
    fun database(id: PersistenceId): String = routing.of(id).first

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> =
        routing.of(id).second.append(id, expected, events)

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = routing.of(id).second.read(id, from)

    /** Deletes in [id]'s database, if that journal prunes; [readTo] is an offset in that database's feed. */
    override fun deleteTo(id: PersistenceId, sequence: Long, readTo: Long) {
        (routing.of(id).second as? JournalPruning)?.deleteTo(id, sequence, readTo)
    }

    companion object {
        /** What a read model [name] saves its offset under for [database]'s feed. */
        fun progress(name: String, database: String): String = "$name@$database"
    }
}

/** Snapshots split across databases as [ShardedJournal] splits events: the same names in the same order co-locate. */
class ShardedSnapshots(databases: List<Pair<String, SnapshotStore>>) : SnapshotStore {
    private val routing = Routing(databases)

    override fun save(id: PersistenceId, sequence: Long, bytes: ByteArray) =
        routing.of(id).second.save(id, sequence, bytes)

    override fun latest(id: PersistenceId): Snapshot? = routing.of(id).second.latest(id)
}

/** MurmurHash3, x86 32-bit, seed 0: copied in rather than depended on, and pinned by known values in its test. */
@Suppress("MagicNumber")
internal fun murmur3(data: ByteArray): Int {
    val c1 = -0x3361d2af
    val c2 = 0x1b873593
    var h = 0
    val blocks = data.size / 4
    for (i in 0 until blocks) {
        val at = i * 4
        var k = (data[at].toInt() and 0xff) or
            ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or
            ((data[at + 3].toInt() and 0xff) shl 24)
        k = Integer.rotateLeft(k * c1, 15) * c2
        h = Integer.rotateLeft(h xor k, 13) * 5 + -0x19ab949c
    }
    var tail = 0
    for (i in data.size - 1 downTo blocks * 4) tail = (tail shl 8) or (data[i].toInt() and 0xff)
    if (data.size and 3 != 0) h = h xor (Integer.rotateLeft(tail * c1, 15) * c2)
    h = h xor data.size
    h = h xor (h ushr 16)
    h *= -0x7a143595
    h = h xor (h ushr 13)
    h *= -0x3d4d51cb
    return h xor (h ushr 16)
}
