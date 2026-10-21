@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The offsets handled since a consumer last committed, and the committing of them: the one commit path every loop
 * over a consumer shares, whether its partitions come from a group or are assigned. Only the polling thread calls
 * [commit] and [close]; a [Handle] may be marked from any thread. A consumer with no group is not [committing]:
 * there is nowhere to commit to, and its handles are marked for nothing.
 */
internal class Commits(private val consumer: KafkaConsumer<*, *>, private val committing: Boolean = true) {

    /** The next offset to commit per partition: written by whichever thread ends the stream, read by the poller. */
    private val handled = Handled()

    /** The group as the last poll left it: read on the polling thread, and handed out with each record. */
    private var group: ConsumerGroupMetadata? = null

    /** Set once this consumer starts to leave its group, which a transaction still to commit has to know. */
    private val leaving = Leaving()

    /** After each poll: the group as it left it, for the records it returned. A consumer with no group has none. */
    fun polled() {
        if (committing) group = consumer.groupMetadata()
    }

    /** [record] with its position, and a handle that marks it handled here. */
    fun <K, V> carrying(record: ConsumerRecord<K, V>): Committed<ConsumerRecord<K, V>> = Committed(
        record,
        Position(record.topic(), record.partition(), record.offset()),
        Offset(handled.marking(TopicPartition(record.topic(), record.partition())), record.offset(), group, leaving),
    )

    /** A refusal an async commit reported that sending again cannot fix, for the polling thread to throw. */
    private var refused: Exception? = null

    /**
     * What was handled on partitions this consumer still owns, sent without waiting for the broker: the commit
     * before each poll, which a poll would otherwise wait on. Its answer arrives in a later poll or commit, on
     * this thread. What the broker could not write for now stays due and goes with the next commit; a refusal
     * for good is thrown here, the next time, as a `commitSync` would have thrown it (spec 0154).
     */
    fun commitAsync() {
        refused?.let { failure ->
            refused = null
            throw failure
        }
        val due = handled.due(consumer.assignment())
        if (due.isEmpty() || !committing) return
        consumer.commitAsync(due.mapValues { (_, next) -> OffsetAndMetadata(next) }) { _, failure ->
            when (failure) {
                null -> due.forEach { (partition, next) -> handled.committed(partition, next) }
                is RetriableException -> Unit
                else -> refused = failure
            }
        }
    }

    /**
     * What was handled on partitions this consumer still owns, committed, and forgotten once it is. Waits for the
     * broker, and for any async commit still on its way: for a rebalance and a close, which must not lose them.
     */
    fun commit(owned: Set<TopicPartition> = consumer.assignment()): Boolean {
        val due = handled.due(owned)
        if (due.isEmpty() || !committing) return false
        consumer.commitSync(due.mapValues { (_, next) -> OffsetAndMetadata(next) })
        // Only if nothing newer was handled meanwhile; a later offset is committed next time.
        due.forEach { (partition, next) -> handled.committed(partition, next) }
        return true
    }

    /** [partitions] lost to a rebalance: what was handled on them is dropped, and so is anything marked later. */
    fun forget(partitions: Collection<TopicPartition>) = partitions.forEach(handled::revoked)

    /**
     * What was handled committed, then the consumer closed, however the commit went: within a few times the round
     * trip to its coordinator, which bounds the leave, rather than waiting behind a fetch the broker holds for
     * `fetch.max.wait.ms` (spec 0164). A commit that failed leaves no round trip, and Kafka's own close.
     */
    fun close() {
        // Waits for a transaction already sending this member's offsets: the member must still be there for it.
        leaving.start()
        // A take that ends a Forks run interrupts the fork reading the source, and that fork closes it: the
        // client would refuse both the commit and the close, and the last records handled be read again.
        val interrupted = Thread.interrupted()
        // Set once the commit has gone through: a close that follows a failed one is Kafka's own.
        var budget: Duration? = null
        try {
            budget = timed {
                // A wake the loop never saw is still pending, and fails the first call that reaches the network.
                val sent = try {
                    commit()
                } catch (_: WakeupException) {
                    commit()
                }
                // Nothing was due: one lookup at the coordinator stands in for the commit's round trip.
                if (!sent && committing) {
                    try {
                        consumer.committed(consumer.assignment())
                    } catch (_: WakeupException) {
                        consumer.committed(consumer.assignment())
                    }
                }
            }
        } finally {
            try {
                budget?.let { within -> consumer.close(CloseOptions.timeout(within)) } ?: consumer.close()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}

/** How long a close may take: [ROUND_TRIPS] times what [step] took, and never less than [CLOSE_FLOOR]. */
private inline fun timed(step: () -> Unit): Duration {
    val started = System.nanoTime()
    step()
    return maxOf(Duration.ofNanos(System.nanoTime() - started).multipliedBy(ROUND_TRIPS), CLOSE_FLOOR)
}

private const val ROUND_TRIPS = 5L
private val CLOSE_FLOOR: Duration = Duration.ofMillis(10)

/**
 * The next offset to commit per partition, and how often each was revoked. A record read before a revoke is the new
 * owner's to commit, and may reach the end of the stream after it, on Pekko more so since it reads ahead: its mark is
 * dropped rather than committed for a partition this consumer may be given back (spec 0154).
 */
private class Handled {
    private val next = ConcurrentHashMap<TopicPartition, Long>()
    private val revokes = ConcurrentHashMap<TopicPartition, Int>()

    /** How a record read now marks [partition]: only while no revoke has come between. */
    fun marking(partition: TopicPartition): Marking {
        val readUnder = revokes[partition] ?: 0
        // In the map's lock for the partition, which a revoke's remove also takes, so a mark cannot land after it.
        return Marking { offset ->
            next.compute(partition) { _, was ->
                if ((revokes[partition] ?: 0) != readUnder) was else maxOf(was ?: offset, offset)
            }
        }
    }

    fun due(owned: Set<TopicPartition>): Map<TopicPartition, Long> = next.filterKeys { it in owned }

    /** Forgotten only if nothing newer was marked meanwhile; a later offset is committed next time. */
    fun committed(partition: TopicPartition, offset: Long) = next.remove(partition, offset)

    fun revoked(partition: TopicPartition) {
        revokes.merge(partition, 1, Int::plus)
        next.remove(partition)
    }
}

private fun interface Marking {
    fun mark(next: Long)
}

/** A record's offset, marked handled for its consumer to commit: the one after it is where the group resumes. */
private class Offset(
    private val marking: Marking,
    private val offset: Long,
    private val polledIn: ConsumerGroupMetadata?,
    private val leaving: Leaving,
) : Handle {
    override fun handled() {
        marking.mark(offset + 1)
    }

    /**
     * The member that polled the record, so a transaction from a member that has since lost the partition is
     * refused. Once the consumer is leaving, the group alone: the member is gone, and a run's last transaction
     * commits after its source has ended.
     */
    // The group-id constructor is deprecated since Kafka 4.2 in favour of the consumer's own groupMetadata(),
    // which the broker refuses once the member has left, aborting every clean stop's last transaction (spec
    // 0125). Kafka 5 makes ConsumerGroupMetadata an interface, which this can implement with the group alone.
    @Suppress("DEPRECATION")
    override fun <T> withGroup(send: (ConsumerGroupMetadata?) -> T): T = leaving.holding { left ->
        send(polledIn?.let { member -> if (left) ConsumerGroupMetadata(member.groupId()) else member })
    }
}

/**
 * Whether a consumer has started to leave its group, under the lock a transaction holds while it sends offsets as
 * that member: the leave waits for the send, and a send after the leave goes with the group alone (spec 0131).
 */
private class Leaving {
    private val lock = ReentrantLock()
    private val left = AtomicBoolean(false)

    fun start() = lock.withLock { left.set(true) }

    fun <T> holding(body: (Boolean) -> T): T = lock.withLock { body(left.get()) }
}
