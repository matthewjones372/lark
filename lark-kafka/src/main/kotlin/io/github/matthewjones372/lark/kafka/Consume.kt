@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import arrow.core.Either
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.blocking
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.Deserializer
import java.util.concurrent.ConcurrentHashMap
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

/** One run's consumer, and the offsets handled since it last committed. */
private class Loop<K, V>(
    properties: Map<String, Any>,
    topics: List<String>,
    key: Deserializer<K>,
    value: Deserializer<V>,
    private val pollTimeout: Duration,
) : ConsumerRebalanceListener {

    private val consumer = KafkaConsumer(properties + (ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false), key, value)

    /** The next offset to commit per partition: written by whichever thread ends the stream, read by this one. */
    private val handled = ConcurrentHashMap<TopicPartition, Long>()

    private var polled: Iterator<ConsumerRecord<K, V>> = emptyList<ConsumerRecord<K, V>>().iterator()

    init {
        consumer.subscribe(topics, this)
    }

    /** The next record; a poll waiting on a quiet topic waits until one arrives or [wake] is called. */
    fun next(): Committed<ConsumerRecord<K, V>> {
        while (!polled.hasNext()) {
            commit()
            polled = consumer.poll(pollTimeout.toJavaDuration()).iterator()
        }
        val record = polled.next()
        val partition = TopicPartition(record.topic(), record.partition())
        return Committed(
            record,
            Position(record.topic(), record.partition(), record.offset()),
            Offset(handled, partition, record.offset()),
        )
    }

    fun wake() = consumer.wakeup()

    fun close() {
        try {
            // A wake the loop never saw is still pending, and would fail this commit rather than a poll.
            try {
                commit()
            } catch (_: WakeupException) {
                commit()
            }
        } finally {
            consumer.close()
        }
    }

    /** What was handled on partitions this consumer still owns, committed, and forgotten once it is. */
    private fun commit(owned: Set<TopicPartition> = consumer.assignment()) {
        val due = handled.filterKeys { it in owned }
        if (due.isEmpty()) return
        consumer.commitSync(due.mapValues { (_, next) -> OffsetAndMetadata(next) })
        // Only if nothing newer was handled meanwhile; a later offset is committed next time.
        due.forEach { (partition, next) -> handled.remove(partition, next) }
    }

    override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
        commit(partitions.toSet())
        partitions.forEach(handled::remove)
        // Records already polled from a partition this consumer has lost are its new owner's to read.
        val lost = partitions.toSet()
        polled = polled.asSequence().filterNot { TopicPartition(it.topic(), it.partition()) in lost }.iterator()
    }

    override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) = Unit
}

/** A record's offset, marked handled for its consumer to commit: the one after it is where the group resumes. */
private class Offset(
    private val handled: ConcurrentHashMap<TopicPartition, Long>,
    private val partition: TopicPartition,
    private val offset: Long,
) : Handle {
    override fun handled() {
        handled.merge(partition, offset + 1, ::maxOf)
    }
}
