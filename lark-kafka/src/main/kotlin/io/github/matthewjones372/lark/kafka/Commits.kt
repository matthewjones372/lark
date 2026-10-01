@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import java.util.concurrent.ConcurrentHashMap

/**
 * The offsets handled since a consumer last committed, and the committing of them: the one commit path every loop
 * over a consumer shares, whether its partitions come from a group or are assigned. Only the polling thread calls
 * [commit] and [close]; a [Handle] may be marked from any thread. A consumer with no group is not [committing]:
 * there is nowhere to commit to, and its handles are marked for nothing.
 */
internal class Commits(private val consumer: KafkaConsumer<*, *>, private val committing: Boolean = true) {

    /** The next offset to commit per partition: written by whichever thread ends the stream, read by the poller. */
    private val handled = ConcurrentHashMap<TopicPartition, Long>()

    /** [record] with its position, and a handle that marks it handled here. */
    fun <K, V> carrying(record: ConsumerRecord<K, V>): Committed<ConsumerRecord<K, V>> = Committed(
        record,
        Position(record.topic(), record.partition(), record.offset()),
        Offset(handled, TopicPartition(record.topic(), record.partition()), record.offset()),
    )

    /** What was handled on partitions this consumer still owns, committed, and forgotten once it is. */
    fun commit(owned: Set<TopicPartition> = consumer.assignment()) {
        val due = handled.filterKeys { it in owned }
        if (due.isEmpty() || !committing) return
        consumer.commitSync(due.mapValues { (_, next) -> OffsetAndMetadata(next) })
        // Only if nothing newer was handled meanwhile; a later offset is committed next time.
        due.forEach { (partition, next) -> handled.remove(partition, next) }
    }

    fun forget(partitions: Collection<TopicPartition>) = partitions.forEach(handled::remove)

    /** What was handled committed, then the consumer closed, however the commit went. */
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
