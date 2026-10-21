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
import java.util.concurrent.atomic.AtomicLong
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
 *
 * Each run records `lark.kafka.consumer.*` (spec 0163) to the metrics in scope here, with the tags bound here.
 */
fun <K, V> Kafka.consume(
    properties: Map<String, Any>,
    vararg topics: Topic,
    key: Deserializer<K>,
    value: Deserializer<V>,
    pollTimeout: Duration = 100.milliseconds,
): Stream<Nothing, Committed<ConsumerRecord<K, V>>> {
    val measuring = Measuring.here()
    return Stream.blocking(
        open = {
            val named = properties.named()
            Loop(named, topics.map { it.name }, key, value, pollTimeout, measuring.consumer(named))
        },
        next = { loop -> loop.next() },
        ready = { loop -> loop.ready() },
        wake = { loop -> loop.wake() },
        close = { loop -> loop.close() },
    )
}

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

/** How many consumers this process has named, as the client numbers the ones it names itself. */
private val named = AtomicLong()

/**
 * [this], with a `client.id` of its own where it names none: two consumers of one group in one process would
 * otherwise share their meters, each overwriting the other's gauges (spec 0163). Named as the client names its own.
 */
private fun Map<String, Any>.named(): Map<String, Any> =
    if (ConsumerConfig.CLIENT_ID_CONFIG in this) {
        this
    } else {
        val group = this[ConsumerConfig.GROUP_ID_CONFIG]?.let { "$it-" }.orEmpty()
        this + (ConsumerConfig.CLIENT_ID_CONFIG to "lark-consumer-$group${named.incrementAndGet()}")
    }

/** One run's consumer of a group's partitions, committing what was handled through [commits]. */
private class Loop<K, V>(
    properties: Map<String, Any>,
    topics: List<String>,
    key: Deserializer<K>,
    value: Deserializer<V>,
    private val pollTimeout: Duration,
    private val meters: ConsumerMeters,
) : ConsumerRebalanceListener {

    private val consumer = KafkaConsumer(properties + (ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false), key, value)

    private val commits = Commits(consumer, meters = meters)

    /** The partitions this consumer owns, as its rebalances have left them: only touched inside a poll or close. */
    private val owned = mutableSetOf<TopicPartition>()

    /** What the last polls returned and the stream has yet to take: the keeper's poll can add to it. */
    private val polled = ArrayDeque<ConsumerRecord<K, V>>()

    // Before the keeper starts, so a subscription the client refuses leaves no keeper running.
    init {
        consumer.subscribe(topics, this)
    }

    private val keeper = Keeper(consumer, properties, meters) { records -> polled.addAll(records) }

    /** The next record; a poll waiting on a quiet topic waits until one arrives or [wake] is called. */
    fun next(): Committed<ConsumerRecord<K, V>> = keeper.touching {
        while (polled.isEmpty()) {
            commits.commitAsync()
            keeper.polling()
            val started = System.nanoTime()
            val records = consumer.poll(pollTimeout.toJavaDuration())
            meters.polled(records, started)
            meters.lagged(consumer)
            polled.addAll(records)
            commits.polled()
        }
        commits.carrying(polled.removeFirst())
    }

    /** Whether the last poll still holds a record, so [next] returns without polling. */
    fun ready() = keeper.touching { polled.isNotEmpty() }

    fun wake() = consumer.wakeup()

    fun close() {
        keeper.stop()
        keeper.closing { commits.close() }
    }

    override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
        commits.commit(partitions.toSet())
        commits.forget(partitions)
        // Records already polled from a partition this consumer has lost are its new owner's to read.
        val lost = partitions.toSet()
        polled.removeAll { TopicPartition(it.topic(), it.partition()) in lost }
        owned.removeAll(lost)
        meters.left(lost)
        meters.rebalanced("revoked", owned.size)
    }

    override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
        keeper.assigned(partitions)
        owned.addAll(partitions)
        meters.rebalanced("assigned", owned.size)
    }
}
