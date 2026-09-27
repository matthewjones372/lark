package io.github.matthewjones372.lark.actor

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
