package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.Counter
import io.github.matthewjones372.lark.Gauge
import io.github.matthewjones372.lark.Histogram
import io.github.matthewjones372.lark.Metrics
import io.github.matthewjones372.lark.NoMetrics
import io.github.matthewjones372.lark.metricTags
import io.github.matthewjones372.lark.metrics
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import java.util.concurrent.atomic.LongAdder

// A duration is recorded in seconds, as lark's `timed` records it and a backend assumes a duration is.
private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * Where a consumer's meters go, and the caller's tags: taken where the consumer is described, as `Measured` takes
 * them, since a backend such as Pekko opens it on a thread that inherited neither.
 */
internal class Measuring(private val metrics: Metrics, private val tags: Map<String, String>) {

    /** One run's meters, tagged with its group and client as [properties] name them; empty where they name none. */
    fun consumer(properties: Map<String, Any>): ConsumerMeters = ConsumerMeters(
        metrics,
        tags + mapOf(
            "group" to properties[ConsumerConfig.GROUP_ID_CONFIG]?.toString().orEmpty(),
            "client" to properties[ConsumerConfig.CLIENT_ID_CONFIG]?.toString().orEmpty(),
        ),
    )

    companion object {
        /** The metrics and tags in scope here. */
        fun here(): Measuring = Measuring(metrics.get(), metricTags.get())
    }
}

/**
 * A consumer's own meters, `lark.kafka.consumer.*` (spec 0163). Those of the consumer as a whole are looked up once;
 * those of a partition at each poll that returns some of it, which a backend answers from its own map.
 */
internal class ConsumerMeters(private val metrics: Metrics, private val tags: Map<String, String>) {

    private val poll: Histogram = metrics.histogram("$PREFIX.poll", tags)
    private val commit: Histogram = metrics.histogram("$PREFIX.commit", tags)
    private val close: Histogram = metrics.histogram("$PREFIX.close", tags)
    private val commitFailures: Counter = metrics.counter("$PREFIX.commit.failures", tags)
    private val assigned: Gauge = metrics.gauge("$PREFIX.assigned", tags)
    private val paused: Gauge = metrics.gauge("$PREFIX.paused", tags)
    private val inFlight: Gauge = metrics.gauge("$PREFIX.in.flight", tags)

    /** Records handed to the stream, counted on the polling thread, and those it has handled, from any thread. */
    private var handed = 0L
    private val handled = LongAdder()

    /** What a poll begun at [started] returned, per partition, and how long it took. */
    fun polled(records: ConsumerRecords<*, *>, started: Long) {
        poll.record(secondsSince(started))
        records.partitions().forEach { partition ->
            val of = tags + mapOf("topic" to partition.topic(), "partition" to partition.partition().toString())
            val returned = records.records(partition)
            metrics.counter("$PREFIX.records", of).increment(returned.size.toDouble())
            val bytes = returned.sumOf { maxOf(it.serializedKeySize(), 0) + maxOf(it.serializedValueSize(), 0) }
            metrics.counter("$PREFIX.bytes", of).increment(bytes.toDouble())
        }
        inFlight.set((handed - handled.sum()).toDouble())
    }

    fun handed() {
        handed++
    }

    fun handled() = handled.increment()

    /** A commit that waited for the broker, begun at [started], however it went. */
    fun committed(started: Long) = commit.record(secondsSince(started))

    fun commitFailed() = commitFailures.increment(1.0)

    fun closed(started: Long) {
        close.record(secondsSince(started))
        inFlight.set((handed - handled.sum()).toDouble())
    }

    /** A rebalance of [kind], assigned or revoked, that left this consumer owning [owned] partitions. */
    fun rebalanced(kind: String, owned: Int) {
        metrics.counter("$PREFIX.rebalances", tags + ("kind" to kind)).increment(1.0)
        assigned.set(owned.toDouble())
    }

    fun paused(partitions: Int) = paused.set(partitions.toDouble())

    private fun secondsSince(started: Long): Double = (System.nanoTime() - started) / NANOS_PER_SECOND

    companion object {
        private const val PREFIX = "lark.kafka.consumer"

        /** Meters nobody reads: a loop that records nothing, such as one over assigned partitions, for now. */
        fun none(): ConsumerMeters = ConsumerMeters(NoMetrics, emptyMap())
    }
}
