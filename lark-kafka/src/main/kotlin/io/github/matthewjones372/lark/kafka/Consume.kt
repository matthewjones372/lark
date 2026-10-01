@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import arrow.core.Either
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.blocking
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.Deserializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/**
 * Every record on [topics] for the group [properties] name, from one `KafkaConsumer` per run, on whichever
 * backend runs it. End it on [runCommitting]; the consumer commits each record it was told is handled.
 *
 * Only the thread that polls touches the consumer: it commits what was handled before each poll, for the
 * partitions it loses in a rebalance, and when the run ends. A `stop()` wakes a poll that is waiting.
 * `enable.auto.commit` is always off.
 */
fun <K, V> Kafka.consume(
    properties: Map<String, Any>,
    vararg topics: Topic,
    key: Deserializer<K>,
    value: Deserializer<V>,
    pollTimeout: Duration = 100.milliseconds,
): Stream<Nothing, Committed<ConsumerRecord<K, V>>> =
    Stream.blocking(
        open = { Loop(properties, topics.map { it.name }, key, value, pollTimeout) },
        next = { loop -> loop.next() },
        wake = { loop -> loop.wake() },
        close = { loop -> loop.close() },
    )

/**
 * [consume] over bytes, each key and value decoded in the stream: a record that cannot be read is a `Left`
 * that still carries its offset, so it is routed rather than ending the consumer.
 */
fun <K, V> Kafka.consume(
    properties: Map<String, Any>,
    vararg topics: Topic,
    key: Decoder<K>,
    value: Decoder<V>,
    pollTimeout: Duration = 100.milliseconds,
): Stream<Nothing, Committed<Either<DecodeError, ConsumerRecord<K, V>>>> =
    consume(
        properties,
        *topics,
        key = ByteArrayDeserializer(),
        value = ByteArrayDeserializer(),
        pollTimeout = pollTimeout,
    )
        .decodedWith(key, value)

/** One run's consumer of a group's partitions, committing what was handled through [commits]. */
private class Loop<K, V>(
    properties: Map<String, Any>,
    topics: List<String>,
    key: Deserializer<K>,
    value: Deserializer<V>,
    private val pollTimeout: Duration,
) : ConsumerRebalanceListener {

    private val consumer = KafkaConsumer(properties + (ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false), key, value)

    private val commits = Commits(consumer)

    private var polled: Iterator<ConsumerRecord<K, V>> = emptyList<ConsumerRecord<K, V>>().iterator()

    init {
        consumer.subscribe(topics, this)
    }

    /** The next record; a poll waiting on a quiet topic waits until one arrives or [wake] is called. */
    fun next(): Committed<ConsumerRecord<K, V>> {
        while (!polled.hasNext()) {
            commits.commit()
            polled = consumer.poll(pollTimeout.toJavaDuration()).iterator()
        }
        return commits.carrying(polled.next())
    }

    fun wake() = consumer.wakeup()

    fun close() = commits.close()

    override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
        commits.commit(partitions.toSet())
        commits.forget(partitions)
        // Records already polled from a partition this consumer has lost are its new owner's to read.
        val lost = partitions.toSet()
        polled = polled.asSequence().filterNot { TopicPartition(it.topic(), it.partition()) in lost }.iterator()
    }

    override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) = Unit
}
