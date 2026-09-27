package io.github.matthewjones372.lark.actor

import java.nio.ByteBuffer

/**
 * One version's event as the next version's (spec 0091): the bytes of an event written as version n, as the bytes of
 * the one or more events of version n+1 it now is. An upgrade reads with the old codec and writes with the next, so a
 * new version adds one upgrade and leaves the older ones as they are.
 */
fun interface Upgrade {
    fun upgrade(bytes: ByteArray): List<ByteArray>
}

/** Bytes no upgrade reads: an event written as a version newer than the codec's, or as none at all. */
class UnreadableEvent internal constructor(message: String) : IllegalStateException(message)

/** What `versioned` writes before a codec's bytes; bytes without it were written before, as version 1. */
private val MARK = "\u0000lark:v".toByteArray()

/**
 * [codec], writing each event as version [current], and reading every older version through [upgrades]: an event
 * of version n goes through the upgrade from n, then from n+1, and so on to [current]. [upgrades] needs one from each
 * version below [current] and none from any other, which is checked here, before any event is read. Bytes written
 * with no version, before a service versioned its codec, are version 1 (spec 0091).
 *
 * An event that upgrades to several is read with [EventCodec.decodeAll], as a persistent entity's replay, a
 * projection and [Journal.events] read; [EventCodec.decode] refuses one.
 */
fun <E> versioned(current: Int, codec: EventCodec<E>, upgrades: Map<Int, Upgrade> = emptyMap()): EventCodec<E> {
    chained(current, upgrades.keys)
    return object : EventCodec<E> {
        override fun encode(event: E): ByteArray = versionedBytes(current, codec.encode(event))

        override fun decode(bytes: ByteArray): E {
            val events = decodeAll(bytes)
            check(events.size == 1) { "an event upgraded to ${events.size}: read it with decodeAll" }
            return events.single()
        }

        override fun decodeAll(bytes: ByteArray): List<E> =
            upgraded(bytes, current, upgrades::getValue).map(codec::decode)
    }
}

/** That [upgrades] lead from every version below [current] to it, one step at a time, and from nowhere else. */
internal fun chained(current: Int, upgrades: Set<Int>) {
    require(current >= 1) { "the current version is counted from 1, was $current" }
    val missing = (1 until current).filterNot(upgrades::contains)
    require(missing.isEmpty()) { "no upgrade from version $missing to the next, so they cannot reach $current" }
    val stray = upgrades.filterNot { it in 1 until current }.sorted()
    require(stray.isEmpty()) { "upgrades from version $stray, which is not below the current $current" }
}

/** [body] with the mark and [version] before it. */
internal fun versionedBytes(version: Int, body: ByteArray): ByteArray =
    ByteBuffer.allocate(MARK.size + Int.SIZE_BYTES + body.size).put(MARK).putInt(version).put(body).array()

/** [bytes] as the bodies of version [current] they now are, through [upgrade] from each version on the way. */
internal fun upgraded(bytes: ByteArray, current: Int, upgrade: (Int) -> Upgrade): List<ByteArray> {
    val marked = bytes.size >= MARK.size + Int.SIZE_BYTES && bytes.copyOf(MARK.size).contentEquals(MARK)
    val version = if (marked) ByteBuffer.wrap(bytes, MARK.size, Int.SIZE_BYTES).int else 1
    val body = if (marked) bytes.copyOfRange(MARK.size + Int.SIZE_BYTES, bytes.size) else bytes
    if (version > current) throw UnreadableEvent("version $version, newer than this codec's $current")
    if (version < 1) throw UnreadableEvent("version $version, which no codec writes")
    return (version until current).fold(listOf(body)) { at, from -> at.flatMap { upgrade(from).upgrade(it) } }
}
