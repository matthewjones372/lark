package io.github.matthewjones372.lark.actor.projection

import io.github.matthewjones372.lark.Clock
import io.github.matthewjones372.lark.actor.EventCodec
import io.github.matthewjones372.lark.actor.FeedEvent
import io.github.matthewjones372.lark.actor.JournalFeed
import io.github.matthewjones372.lark.actor.PersistenceId
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.stream.Failing
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.blocking
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.runFold
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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

/**
 * One event a projection follows: whose it is, its sequence number there, its [value], and its [offset] in the feed.
 * The offset is saved by `runProjecting` once the element reaches the end of the stream.
 */
class Followed<out A : Any> internal constructor(
    val id: PersistenceId,
    val sequence: Long,
    val value: A,
    val offset: Long,
    internal val progress: Progress,
) {
    internal fun <B : Any> carrying(value: B) = Followed(id, sequence, value, offset, progress)
}

/** Which read model an element belongs to, and where its offset goes. */
internal class Progress(val name: String, val offsets: OffsetStore)

/** Where a projection starts: [follow]. */
object Projection {

    /**
     * Every event of [kind] from [feed], decoded by [codec], from the one after the offset [offsets] holds for [name]
     * or from the first. Once it has caught up it asks the feed again every [every], on lark's clock, and it never
     * ends by itself: a run's `stop()` wakes it. Each event is handled at least once; end it with `runProjecting`.
     */
    @Suppress("LongParameterList")
    fun <E : Any> follow(
        feed: JournalFeed,
        kind: String,
        codec: EventCodec<E>,
        offsets: OffsetStore,
        name: String,
        every: Duration = 1.seconds,
        batch: Int = 256,
    ): Stream<Nothing, Followed<E>> {
        require(batch > 0) { "a batch of $batch reads nothing" }
        val time = clock.get()
        val progress = Progress(name, offsets)
        return Stream.blocking(
            open = { Cursor(feed, kind, offsets.load(name) ?: 0, every, batch, time) },
            next = { cursor ->
                val event = cursor.next()
                Followed(event.id, event.sequence, codec.decode(event.bytes), event.offset, progress)
            },
            wake = Cursor::wake,
            close = { },
        )
    }
}

/** One run's place in the feed: what it has read and not yet handed on, and the thread that reads it. */
private class Cursor(
    private val feed: JournalFeed,
    private val kind: String,
    private var last: Long,
    private val every: Duration,
    private val batch: Int,
    private val time: Clock,
) {
    private val read = ArrayDeque<FeedEvent>()

    @Volatile
    private var reader: Thread? = null

    /** The next event, waiting [every] at a time until there is one; a [wake] ends the wait by interrupting it. */
    fun next(): FeedEvent {
        reader = Thread.currentThread()
        while (read.isEmpty()) {
            val more = feed.after(kind, last, batch)
            if (more.isEmpty()) time.sleep(every) else read += more
        }
        return read.removeFirst().also { last = it.offset }
    }

    fun wake() {
        reader?.interrupt()
    }
}

/** [f] on each event, keeping its offset. */
fun <E, A : Any, B : Any> Stream<E, Followed<A>>.mapFollowed(f: (Followed<A>) -> B): Stream<E, Followed<B>> =
    map { event -> event.carrying(f(event)) }

/** [f] on each event, which may `fail` the run, keeping its offset. */
@JvmName("mapFollowedOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, Followed<A>>.mapFollowedOrFail(
    f: Failing<F>.(Followed<A>) -> B,
): Stream<F, Followed<B>> = mapOrFail<F, Followed<A>, Followed<B>> { event -> event.carrying(f(this, event)) }

/** As above, for a stream whose failure is already named. */
fun <E, A : Any, B : Any> Stream<E, Followed<A>>.mapFollowedOrFail(
    f: Failing<E>.(Followed<A>) -> B,
): Stream<E, Followed<B>> = mapOrFail<E, Followed<A>, Followed<B>> { event -> event.carrying(f(this, event)) }

/**
 * A run that saves each event's offset once its element reaches the end of the stream, so a projection started
 * again goes on after the last one saved. It answers how many elements reached the end.
 */
fun <E> Stream<E, Followed<*>>.runProjecting(): Run<E, Long> =
    map { event ->
        event.progress.offsets.save(event.progress.name, event.offset)
        1L
    }.runFold(0L, Long::plus)
