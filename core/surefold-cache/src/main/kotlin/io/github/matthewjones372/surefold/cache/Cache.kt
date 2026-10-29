package io.github.matthewjones372.surefold.cache

import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.ScheduleStep
import io.github.matthewjones372.surefold.SingleFlight
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.logWarn
import io.github.matthewjones372.surefold.surefoldFuture
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** What a cache is told when a value may have changed: one key, or everything, when a change may have been missed. */
sealed interface Invalidation<out K> {
    data class Key<K>(val key: K) : Invalidation<K>

    data object All : Invalidation<Nothing>
}

/** What a cache has done since it was made: each `get` is a hit or a miss, and each miss a load. */
data class CacheStats(
    val hits: Long,
    val misses: Long,
    val loads: Long,
    val failures: Long,
    val stale: Long,
    val evictions: Long,
    val layerHits: Long,
    val layerFailures: Long,
    val size: Int,
)

/**
 * A function with a memory (spec 0246): [lookup] is given once, here, and [get] answers from memory while an answer is
 * young, and looks up again once it is not. However many ask for one key at once, one lookup runs, through
 * `SingleFlight`. A zero [ttl] shares the answer without keeping it or writing layers; an infinite [ttl] keeps a
 * fresh result only in memory without a deadline. Other answers are kept as long as [ttl] says, a `Left` included,
 * so a source that is down is asked once per its `Left`'s ttl, not once per caller. When a lookup fails and the last
 * `Right` for the key is younger than [stale], that `Right` answers instead, until [stale] runs out. Ages are read
 * from the clock bound where the cache is made, so a `TestClock` moves every one.
 *
 * At most [capacity] keys are kept. Past it, a new key is kept only if it has been asked for more often lately than
 * the least recently read key it would push out (TinyLFU), so a scan of keys read once cannot flush the keys read all
 * day. [onEvict] is handed each value no entry holds any more: pushed out, invalidated, or replaced by a newer one.
 *
 * Under its own memory, a cache asks [layers] in turn before it looks up: a hit in one is written into its own memory
 * and every nearer layer, keeping its expiry, so a value is never younger near than it was far. A good value looked up
 * with a finite ttl is written into every layer; a failure is kept in its own memory only.
 *
 * [retry] runs a failing lookup again inside one lookup, after the delays it answers on the cache's clock, before its
 * failure is kept; every caller waiting on that key shares the retries. [refreshing] looks a key up again ahead of its
 * expiry: after each lookup it is stepped with the result, and its delay is when the key is looked up again in the
 * background, for as long as someone has read it since the last time; a key nobody read is left to expire. A cache is
 * closed when its owner is done with it, which stops every refresh and every lookup in flight.
 */
class Cache<K : Any, E, V>(
    private val ttl: (Either<E, V>) -> Duration,
    private val stale: Duration = Duration.ZERO,
    capacity: Int = DEFAULT_CAPACITY,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
    private val layers: List<Layer<K, V>> = emptyList(),
    private val retry: Schedule<E, *>? = null,
    private val refreshing: Schedule<Either<E, V>, *>? = null,
    private val lookup: (K) -> Either<E, V>,
) : AutoCloseable {
    init {
        require(!stale.isNegative()) { "a stale value cannot be kept for $stale" }
    }

    // A key's answer, until when, and the last good value behind it, kept to answer for it while lookups fail.
    private class Entry<E, V>(val answer: Either<E, V>, val until: Instant?, val good: Good<V>?, val isStale: Boolean) {
        fun young(now: Instant): Boolean = until == null || now < until
    }

    private class Good<V>(val value: V, val at: Instant)

    private val time = clock.get()
    private val evictions = AtomicLong()
    private val entries = Memory<K, Entry<E, V>>(capacity) { key, gone ->
        evictions.incrementAndGet()
        gone.good?.let { onEvict(key, it.value) }
    }
    private val flights = SingleFlight<K, E, V>("surefold.cache")

    // Moved on by every invalidation, so a lookup that began before one does not write its answer after it.
    private val generation = AtomicLong()

    private val hits = AtomicLong()
    private val misses = AtomicLong()
    private val loads = AtomicLong()
    private val failures = AtomicLong()
    private val staleAnswers = AtomicLong()
    private val layerHits = AtomicLong()
    private val layerFailures = AtomicLong()

    // Each key refreshed ahead: the schedule's next step, whether it was read since its last lookup, and its sleeper.
    private class Ahead<E, V>(val next: ScheduleStep<Either<E, V>, *>) {
        @Volatile
        var read = false

        @Volatile
        var sleeper: Future<Unit>? = null
    }

    private val ahead = ConcurrentHashMap<K, Ahead<E, V>>()

    @Volatile
    private var closed = false

    /** [key]'s answer: from memory while it is young, else from one [lookup] however many ask. */
    fun get(key: K): Either<E, V> {
        check(!closed) { "the cache has closed" }
        val held = entries.read(key)
        if (held != null && held.young(time.now())) {
            ahead[key]?.read = true
            hits.incrementAndGet()
            if (held.isStale) staleAnswers.incrementAndGet()
            return held.answer
        }
        misses.incrementAndGet()
        return flights.run(key) { load(key) }
    }

    /** Looks [key] up again in the background now; until it answers, and if it fails, `get` answers what it has. */
    fun refresh(key: K) {
        check(!closed) { "the cache has closed" }
        background { flights.run(key) { load(key, refreshing = true) } }
    }

    /** Stops every refresh ahead and every lookup in flight; a `get` after this is refused. */
    override fun close() {
        closed = true
        ahead.values.forEach { it.sleeper?.cancel(true) }
        ahead.clear()
        flights.cancelAll()
    }

    // How many keys are refreshed ahead now, for a test that waits for a key to be let go.
    internal fun ahead(): Int = ahead.size

    /**
     * Forgets [what] in every layer, farthest first so a nearer one is not refilled from a stale copy, and then in its
     * own memory. The next `get` for it looks up again; a lookup in flight answers its callers, unkept.
     */
    fun invalidate(what: Invalidation<K>) {
        generation.incrementAndGet()
        layers.asReversed().forEach { layer ->
            when (what) {
                is Invalidation.Key -> safely("remove ${what.key}") { layer.remove(what.key) }
                Invalidation.All -> safely("clear") { layer.clear() }
            }
        }
        when (what) {
            is Invalidation.Key -> entries.remove(what.key)
            Invalidation.All -> entries.clear()
        }
    }

    // How many callers wait on [key]'s lookup now, for a test that must let it end only once all have joined it.
    internal fun waiting(key: K): Int = flights.waiting(key)

    fun stats(): CacheStats =
        CacheStats(
            hits.get(), misses.get(), loads.get(), failures.get(), staleAnswers.get(), evictions.get(),
            layerHits.get(), layerFailures.get(), entries.size,
        )

    private fun load(key: K, refreshing: Boolean = false): Either<E, V> {
        val began = generation.get()
        if (!refreshing) fromLayers(key, began)?.let { return it }
        val result = retried(key)
        val now = time.now()
        val young = entries.peek(key)?.takeIf { refreshing && it.young(now) }
        val retention = ttl(result)
        if (result is Either.Left && young != null) {
            // A refresh that failed keeps what it was refreshing, which is still young.
            failures.incrementAndGet()
            if (generation.get() == began) {
                if (retention == Duration.ZERO) stopAhead(key) else ahead(key, result)
            }
            return young.answer
        }
        val entry = when (result) {
            is Either.Right -> Entry<E, V>(result, deadline(now, retention), Good(result.value, now), false)
            is Either.Left -> failed(result, entries.peek(key)?.good, now, retention)
        }
        if (entry.isStale) staleAnswers.incrementAndGet()
        if (generation.get() == began) publish(key, result, entry, now, retention)
        return entry.answer
    }

    private fun publish(key: K, result: Either<E, V>, entry: Entry<E, V>, now: Instant, retention: Duration) {
        if (retention == Duration.ZERO) {
            stopAhead(key)
            entries.remove(key)
            return
        }
        keep(key, entry)
        if (result is Either.Right && entry.until != null) {
            val stored = Stored(result.value, now, entry.until)
            layers.forEach { layer -> safely("write $key") { layer.write(key, stored) } }
        }
        ahead(key, result)
    }

    // The lookup, run again on a failure for as long as [retry] carries on, sleeping on the cache's clock between.
    private fun retried(key: K): Either<E, V> {
        loads.incrementAndGet()
        var result = lookup(key)
        var next = retry?.step ?: return result
        while (result is Either.Left) {
            when (val decision = next(result.value)) {
                is Schedule.Decision.Done -> return result

                is Schedule.Decision.Continue -> {
                    time.sleep(decision.delay)
                    next = decision.step
                    loads.incrementAndGet()
                    result = lookup(key)
                }
            }
        }
        return result
    }

    // Steps [refreshing] with [result], and sleeps until its delay to look [key] up again, if it was read meanwhile.
    private fun ahead(key: K, result: Either<E, V>) {
        val schedule = refreshing ?: return
        val step = ahead.remove(key)?.next ?: schedule.step
        val decision = step(result) as? Schedule.Decision.Continue ?: return
        if (closed || decision.delay == Duration.INFINITE) return
        // In the map before its sleeper starts, so a sleeper with no delay finds itself there.
        val mine = Ahead<E, V>(decision.step).also { ahead[key] = it }
        mine.sleeper = background {
            time.sleep(decision.delay)
            if (ahead[key] === mine && mine.read && !closed) flights.run(key) { load(key, refreshing = true) }
            ahead.remove(key, mine)
        }
    }

    private fun stopAhead(key: K) {
        ahead.remove(key)?.sleeper?.cancel(true)
    }

    private fun background(block: () -> Unit): Future<Unit> = surefoldFuture(block)

    // The first layer holding a young value for [key], written into memory and every nearer layer on the way back.
    private fun fromLayers(key: K, began: Long): Either<E, V>? {
        val now = time.now()
        layers.forEachIndexed { at, layer ->
            val stored = safely("read $key") { layer.read(key) }?.takeIf { now < it.expiresAt } ?: return@forEachIndexed
            layerHits.incrementAndGet()
            val answer = Either.Right(stored.value)
            val retention = ttl(answer)
            if (generation.get() == began) {
                if (retention == Duration.ZERO) {
                    stopAhead(key)
                    entries.remove(key)
                    return answer
                }
                val good = Good(stored.value, stored.storedAt)
                keep(key, Entry(answer, stored.expiresAt, good, false))
                layers.take(at).forEach { nearer -> safely("write $key") { nearer.write(key, stored) } }
            }
            return answer
        }
        return null
    }

    private fun keep(key: K, entry: Entry<E, V>) {
        val replaced = entries.write(key, entry)
        // A stale answer keeps the good value it answers with, so only a value nothing holds any more goes.
        replaced?.good?.takeIf { it !== entry.good }?.let { onEvict(key, it.value) }
    }

    @Suppress("TooGenericExceptionCaught") // a layer that fails is a miss, logged and counted, never the caller's error
    private inline fun <A> safely(what: String, block: () -> A): A? = try {
        block()
    } catch (failed: Exception) {
        layerFailures.incrementAndGet()
        logWarn("surefold-cache: a layer could not $what: $failed")
        null
    }

    // A failed lookup: the last good value while it is younger than [stale], kept no longer than either allows.
    private fun failed(result: Either<E, V>, good: Good<V>?, now: Instant, retention: Duration): Entry<E, V> {
        failures.incrementAndGet()
        val until = deadline(now, retention)
        val staleUntil = good?.let { it.at + stale.toJavaDuration() }
        return if (good != null && staleUntil != null && now < staleUntil) {
            Entry(Either.Right(good.value), until?.let { minOf(it, staleUntil) } ?: staleUntil, good, true)
        } else {
            Entry(result, until, null, false)
        }
    }

    private fun deadline(now: Instant, retention: Duration): Instant? =
        if (retention == Duration.INFINITE) null else now + retention.toJavaDuration()
}

/** How many keys a cache keeps unless it is told otherwise. */
const val DEFAULT_CAPACITY = 10_000
