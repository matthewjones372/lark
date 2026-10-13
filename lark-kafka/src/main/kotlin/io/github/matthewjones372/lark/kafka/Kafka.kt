@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import arrow.core.Either
import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.runFold
import org.apache.kafka.clients.consumer.ConsumerRecord

/**
 * A topic by name, so a subscription's arguments cannot be mistaken for a group id or a server.
 *
 * A data class and not a value class: Kotlin refuses a value class as a vararg.
 */
data class Topic(val name: String)

/** Where a subscription starts: [consume] here on any backend, and `subscribe` in lark-kafka-pekko. */
object Kafka

/**
 * A run described that marks each record handled once its element reaches the end of the stream, for records
 * from [Kafka.consume]; the consumer commits them. It answers how many elements reached the end.
 */
fun <E> Stream<E, Committed<*>>.runCommitting(): Run<E, Long> =
    // Counted from Long rather than folded over Committed, which the guard beside runFold refuses.
    map { element ->
        element.handle?.handled()
        1L
    }.runFold(0L, Long::plus)

/** Each record's key and value decoded from bytes in the stream, for a module with a byte-level source. */
@KafkaSpi
fun <E, K, V> Stream<E, Committed<ConsumerRecord<ByteArray?, ByteArray?>>>.decodedWith(
    key: Decoder<K>,
    value: Decoder<V>,
): Stream<E, Committed<Either<DecodeError, ConsumerRecord<K, V>>>> =
    mapRecord { record -> record.decoded(key, value) }
