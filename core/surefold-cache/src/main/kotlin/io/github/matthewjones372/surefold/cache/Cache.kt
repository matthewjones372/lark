package io.github.matthewjones372.surefold.cache

import io.github.matthewjones372.surefold.SingleFlight
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.fail.Either
import java.time.Instant
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
    val size: Int,
)

/**
 * A function with a memory (spec 0246): [lookup] is given once, here, and [get] answers from memory while an answer is
 * young, and looks up again once it is not. However many ask for one key at once, one lookup runs, through
 * `SingleFlight`. Every answer is kept for as long as [ttl] says for it, a `Left` included, so a source that is down is
 * asked once per its `Left`'s ttl, not once per caller. When a lookup fails and the last `Right` for the key is younger
 * than [stale], that `Right` answers instead, until [stale] runs out. Ages are read from the clock bound where the
 * cache is made, so a `TestClock` moves every one.
 *
 * At most [capacity] keys are kept. Past it, a new key is kept only if it has been asked for more often lately than
 * the least recently read key it would push out (TinyLFU), so a scan of keys read once cannot flush the keys read all
 * day. [onEvict] is handed each value no entry holds any more: pushed out, invalidated, or replaced by a newer one.
 */
class Cache<K : Any, E, V>(
    private val ttl: (Either<E, V>) -> Duration,
    private val stale: Duration = Duration.ZERO,
    capacity: Int = DEFAULT_CAPACITY,
    private val onEvict: (K, V) -> Unit = { _, _ -> },
    private val lookup: (K) -> Either<E, V>,
) {
    init {
        require(!stale.isNegative()) { "a stale value cannot be kept for $stale" }
    }

    // A key's answer, until when, and the last good value behind it, kept to answer for it while lookups fail.
    private class Entry<E, V>(val answer: Either<E, V>, val until: Instant, val good: Good<V>?, val isStale: Boolean)

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

    /** [key]'s answer: from memory while it is young, else from one [lookup] however many ask. */
    fun get(key: K): Either<E, V> {
        val held = entries.read(key)
        if (held != null && time.now() < held.until) {
            hits.incrementAndGet()
            if (held.isStale) staleAnswers.incrementAndGet()
            return held.answer
        }
        misses.incrementAndGet()
        return flights.run(key) { load(key) }
    }

    /** Forgets [what], so the next `get` for it looks up again. A lookup in flight answers its callers, unkept. */
    fun invalidate(what: Invalidation<K>) {
        generation.incrementAndGet()
        when (what) {
            is Invalidation.Key -> entries.remove(what.key)
            Invalidation.All -> entries.clear()
        }
    }

    // How many callers wait on [key]'s lookup now, for a test that must let it end only once all have joined it.
    internal fun waiting(key: K): Int = flights.waiting(key)

    fun stats(): CacheStats =
        CacheStats(
            hits.get(), misses.get(), loads.get(), failures.get(), staleAnswers.get(), evictions.get(), entries.size,
        )

    private fun load(key: K): Either<E, V> {
        val began = generation.get()
        loads.incrementAndGet()
        val result = lookup(key)
        val now = time.now()
        val entry = when (result) {
            is Either.Right -> Entry<E, V>(result, now + ttl(result).toJavaDuration(), Good(result.value, now), false)
            is Either.Left -> failed(result, entries.peek(key)?.good, now)
        }
        if (entry.isStale) staleAnswers.incrementAndGet()
        if (generation.get() == began) {
            val replaced = entries.write(key, entry)
            // A stale answer keeps the good value it answers with, so only a value nothing holds any more goes.
            replaced?.good?.takeIf { it !== entry.good }?.let { onEvict(key, it.value) }
        }
        return entry.answer
    }

    // A failed lookup: the last good value while it is younger than [stale], kept no longer than either allows.
    private fun failed(result: Either<E, V>, good: Good<V>?, now: Instant): Entry<E, V> {
        failures.incrementAndGet()
        val until = now + ttl(result).toJavaDuration()
        val staleUntil = good?.let { it.at + stale.toJavaDuration() }
        return if (good != null && staleUntil != null && now < staleUntil) {
            Entry(Either.Right(good.value), minOf(until, staleUntil), good, true)
        } else {
            Entry(result, until, null, false)
        }
    }
}

/** How many keys a cache keeps unless it is told otherwise. */
const val DEFAULT_CAPACITY = 10_000
