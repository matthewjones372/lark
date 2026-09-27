package io.github.matthewjones372.lark.actor

import java.util.concurrent.ConcurrentHashMap

/** One event as a feed answers it: its place in the feed, whose it is, its sequence number there, and its bytes. */
class FeedEvent(val offset: Long, val id: PersistenceId, val sequence: Long, val bytes: ByteArray)

/**
 * Every event of one kind, across its ids, in one order (spec 0075): what a read model follows. A journal that can
 * answer it implements this beside [Journal]. Offsets grow with each append and never repeat; they need not be
 * consecutive, since an append that did not happen can leave a gap.
 */
interface JournalFeed {
    /** Up to [limit] events of [kind] whose offsets are greater than [offset], in offset order. */
    fun after(kind: String, offset: Long, limit: Int): List<FeedEvent>
}

/**
 * Where a read model's progress is kept (spec 0075): the offset of the last event it handled, by the read model's
 * name. A store that cannot write throws.
 */
interface OffsetStore {
    /** The last offset saved for [name], or null when none was. */
    fun load(name: String): Long?

    fun save(name: String, offset: Long)
}

/** Offsets in memory, for tests and for a read model that is rebuilt from the start on every run. */
class InMemoryOffsets : OffsetStore {
    private val kept = ConcurrentHashMap<String, Long>()

    override fun load(name: String): Long? = kept[name]

    override fun save(name: String, offset: Long) {
        kept[name] = offset
    }
}
