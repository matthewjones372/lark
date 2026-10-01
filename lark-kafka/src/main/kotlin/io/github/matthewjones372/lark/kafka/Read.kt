@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import arrow.core.Either
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.blocking
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.Deserializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/** Where a [Kafka.read] starts in its partition. */
sealed interface From {
    /** After the last offset the `group.id` committed; with none committed, where `auto.offset.reset` says. */
    data object Committed : From

    data object Earliest : From

    data object Latest : From

    data class Offset(val offset: Long) : From
}

/** Where a [Kafka.read] ends. */
sealed interface Until {
    data object Never : Until

    /** At the partition's end offset as it was when the read opened: what was written before it, and no more. */
    data object EndAtStart : Until
}

/**
 * Every record of one [partition] of [topic], from [from] until [until], read by a consumer it is assigned to, so no
 * rebalance moves it. End it on [runCommitting]: with a `group.id` in [properties], each record is committed once it
 * is handled, as [Kafka.consume] does; without one, nothing is committed and [From.Committed] is refused here.
 */
fun <K, V> Kafka.read(
    properties: Map<String, Any>,
    topic: Topic,
    partition: Int,
    key: Deserializer<K>,
    value: Deserializer<V>,
    from: From,
    until: Until,
    pollTimeout: Duration = 100.milliseconds,
): Stream<Nothing, Committed<ConsumerRecord<K, V>>> {
    val grouped = properties[ConsumerConfig.GROUP_ID_CONFIG] != null
    require(grouped || from != From.Committed) {
        "Kafka.read from the committed offset needs a group.id in its properties, and ${topic.name} was read with none"
    }
    return Stream.blocking(
        open = { Assigned(properties, TopicPartition(topic.name, partition), key, value, from, until, pollTimeout) },
        next = { read -> read.next() },
        wake = { read -> read.wake() },
        close = { read -> read.close() },
    )
}

/** [read] over bytes, each key and value decoded in the stream, as the [Decoder] form of [Kafka.consume] is. */
fun <K, V> Kafka.read(
    properties: Map<String, Any>,
    topic: Topic,
    partition: Int,
    key: Decoder<K>,
    value: Decoder<V>,
    from: From,
    until: Until,
    pollTimeout: Duration = 100.milliseconds,
): Stream<Nothing, Committed<Either<DecodeError, ConsumerRecord<K, V>>>> =
    read(properties, topic, partition, ByteArrayDeserializer(), ByteArrayDeserializer(), from, until, pollTimeout)
        .decodedWith(key, value)

/** One run's consumer of one assigned partition, which ends at [end] if it has one. */
private class Assigned<K, V>(
    properties: Map<String, Any>,
    private val partition: TopicPartition,
    key: Deserializer<K>,
    value: Deserializer<V>,
    from: From,
    until: Until,
    private val pollTimeout: Duration,
) {
    private val consumer = KafkaConsumer(properties + (ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false), key, value)

    private val commits = Commits(consumer, committing = properties[ConsumerConfig.GROUP_ID_CONFIG] != null)

    private var polled: Iterator<ConsumerRecord<K, V>> = emptyList<ConsumerRecord<K, V>>().iterator()

    private val end: Long?

    init {
        consumer.assign(listOf(partition))
        when (from) {
            From.Committed -> consumer.committed(setOf(partition))[partition]?.let { consumer.seek(partition, it) }
            From.Earliest -> consumer.seekToBeginning(listOf(partition))
            From.Latest -> consumer.seekToEnd(listOf(partition))
            is From.Offset -> consumer.seek(partition, from.offset)
        }
        end = when (until) {
            Until.Never -> null
            Until.EndAtStart -> consumer.endOffsets(listOf(partition)).getValue(partition)
        }
    }

    /** The next record, or `null` once the position has reached [end]; a read with no end waits for one. */
    fun next(): Committed<ConsumerRecord<K, V>>? {
        while (!polled.hasNext()) {
            if (end != null && consumer.position(partition) >= end) return null
            commits.commit()
            val limit = end ?: Long.MAX_VALUE
            polled = consumer.poll(pollTimeout.toJavaDuration()).records(partition).filter { it.offset() < limit }
                .iterator()
            commits.polled()
        }
        return commits.carrying(polled.next())
    }

    fun wake() = consumer.wakeup()

    fun close() = commits.close()
}
