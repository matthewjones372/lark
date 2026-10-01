@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import org.apache.kafka.clients.consumer.ConsumerGroupMetadata
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The offsets handled since a consumer last committed, and the committing of them: the one commit path every loop
 * over a consumer shares, whether its partitions come from a group or are assigned. Only the polling thread calls
 * [commit] and [close]; a [Handle] may be marked from any thread.
 */
internal class Commits(private val consumer: KafkaConsumer<*, *>) {

    /** The next offset to commit per partition: written by whichever thread ends the stream, read by the poller. */
    private val handled = ConcurrentHashMap<TopicPartition, Long>()

    /** The group as the last poll left it: read on the polling thread, and handed out with each record. */
    private var group: ConsumerGroupMetadata? = null

    /** Set once this consumer starts to leave its group, which a transaction still to commit has to know. */
    private val leaving = AtomicBoolean(false)

    /** After each poll: the group as it left it, for the records it returned. */
    fun polled() {
        group = consumer.groupMetadata()
    }

    /** [record] with its position, and a handle that marks it handled here. */
    fun <K, V> carrying(record: ConsumerRecord<K, V>): Committed<ConsumerRecord<K, V>> = Committed(
        record,
        Position(record.topic(), record.partition(), record.offset()),
        Offset(handled, TopicPartition(record.topic(), record.partition()), record.offset(), group, leaving),
    )

    /** What was handled on partitions this consumer still owns, committed, and forgotten once it is. */
    fun commit(owned: Set<TopicPartition> = consumer.assignment()) {
        val due = handled.filterKeys { it in owned }
        if (due.isEmpty()) return
        consumer.commitSync(due.mapValues { (_, next) -> OffsetAndMetadata(next) })
        // Only if nothing newer was handled meanwhile; a later offset is committed next time.
        due.forEach { (partition, next) -> handled.remove(partition, next) }
    }

    fun forget(partitions: Collection<TopicPartition>) = partitions.forEach(handled::remove)

    /** What was handled committed, then the consumer closed, however the commit went. */
    fun close() {
        leaving.set(true)
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
    private val polledIn: ConsumerGroupMetadata?,
    private val leaving: AtomicBoolean,
) : Handle {
    override fun handled() {
        handled.merge(partition, offset + 1, ::maxOf)
    }

    /**
     * The member that polled the record, so a transaction from a member that has since lost the partition is
     * refused. Once the consumer is leaving, the group alone: the member is gone, and a run's last transaction
     * commits after its source has ended.
     */
    override fun group(): ConsumerGroupMetadata? =
        polledIn?.let { member -> if (leaving.get()) ConsumerGroupMetadata(member.groupId()) else member }
}
