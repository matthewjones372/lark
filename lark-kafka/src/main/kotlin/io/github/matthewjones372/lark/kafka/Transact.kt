@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.groupedWithin
import io.github.matthewjones372.lark.stream.mapPar
import io.github.matthewjones372.lark.stream.runFold
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.AuthorizationException
import org.apache.kafka.common.errors.OutOfOrderSequenceException
import org.apache.kafka.common.errors.ProducerFencedException
import org.apache.kafka.common.serialization.Serializer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.apache.kafka.clients.producer.Producer as Client

/**
 * One `KafkaProducer` with a `transactional.id`, whose transactions [runTransactionally] begins and commits.
 * Its transactions run one at a time, so one run uses it; a second run waits for the first's transaction to end.
 * Whoever opens it closes it.
 */
class TransactionalProducer<K, V> @KafkaSpi constructor(private val client: Client<K, V>) : AutoCloseable {

    init {
        // Fences any earlier producer with the same transactional.id, and finishes what it left open.
        client.initTransactions()
    }

    /**
     * [records] and [offsets] in one transaction, committed together or not at all. A failure the transaction can
     * be aborted after is aborted and thrown; one that leaves the producer unusable, fenced by a newer one with
     * the same id among them, is thrown as it is.
     */
    // As wide as a transaction: whatever failed it, it is aborted if it can be, and the failure goes on unchanged.
    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    internal fun commit(
        records: List<ProducerRecord<K, V>>,
        offsets: Map<TopicPartition, OffsetAndMetadata>,
        group: () -> ConsumerGroupMetadata,
    ) {
        client.beginTransaction()
        try {
            records.map(client::send).forEach { sent -> sent.awaited() }
            if (offsets.isNotEmpty()) client.sendOffsetsToTransaction(offsets, group())
            client.commitTransaction()
        } catch (failed: Exception) {
            if (failed.abortable()) abort(failed)
            throw failed
        }
    }

    /** The transaction aborted; an abort that fails too rides on what failed the transaction. */
    @Suppress("TooGenericExceptionCaught")
    private fun abort(failed: Exception) {
        try {
            client.abortTransaction()
        } catch (alsoFailed: Exception) {
            failed.addSuppressed(alsoFailed)
        }
    }

    override fun close() = client.close()
}

/**
 * A producer from [properties] whose transactions are [transactionalId]'s: a stable id per instance of the
 * service, so a restart fences the instance it replaces.
 */
fun <K, V> Kafka.transactionalProducer(
    properties: Map<String, Any>,
    transactionalId: String,
    key: Serializer<K>,
    value: Serializer<V>,
): TransactionalProducer<K, V> {
    val transactional = properties + (ProducerConfig.TRANSACTIONAL_ID_CONFIG to transactionalId)
    return TransactionalProducer(KafkaProducer(transactional, key, value))
}

/**
 * Each record's outputs, the records [to] makes of it, written in the same transaction as its offset: the
 * outputs and the offset are committed together or not at all, so a record read is written exactly once to
 * a reader with `isolation.level=read_committed`. Records are taken in batches of up to [batch], or what came
 * within [within], one transaction each, and each element is how many records one committed. A transaction
 * that fails is aborted and is a defect: `restartOnDefect` after this reads again from the last offsets a
 * transaction committed.
 *
 * For records from [Kafka.consume]: the transaction commits their offsets, and the consumer never does.
 */
fun <E, A : Any, K, V> Stream<E, Committed<A>>.transacted(
    producer: TransactionalProducer<K, V>,
    batch: Int = DEFAULT_BATCH,
    within: Duration = DEFAULT_WITHIN,
    to: (A) -> Iterable<ProducerRecord<K, V>>,
): Stream<E, Long> =
    groupedWithin(batch, within)
        // One transaction at a time, off the stream's own thread, because committing one blocks.
        .mapPar(1) { taken: List<Committed<A>> ->
            val outputs = taken.flatMap { c -> c.annotated(to).toList() }
            producer.commit(outputs, taken.offsets()) { taken.group() }
            taken.count { it.handle != null }.toLong()
        }

/** [transacted], run to its end: how many records its transactions committed. */
fun <E, A : Any, K, V> Stream<E, Committed<A>>.runTransactionally(
    producer: TransactionalProducer<K, V>,
    batch: Int = DEFAULT_BATCH,
    within: Duration = DEFAULT_WITHIN,
    to: (A) -> Iterable<ProducerRecord<K, V>>,
): Run<E, Long> = transacted(producer, batch, within, to).runFold(0L, Long::plus)

/**
 * The offset after the last of each partition's records whose every element is here: an element that is not
 * its record's last carries no handle, and its record's offset waits for the transaction its last is in.
 */
private fun List<Committed<*>>.offsets(): Map<TopicPartition, OffsetAndMetadata> =
    filter { it.handle != null }
        .groupBy { TopicPartition(it.position.topic, it.position.partition) }
        .mapValues { (_, records) -> OffsetAndMetadata(records.maxOf { it.position.offset } + 1) }

/**
 * The group the batch was read in: the first record's, so a batch that straddles a rebalance is committed in the
 * generation it began in, and the broker refuses it rather than committing a partition this consumer has lost.
 */
private fun List<Committed<*>>.group(): ConsumerGroupMetadata =
    firstNotNullOfOrNull { it.handle?.group() }
        ?: error("runTransactionally commits offsets in a transaction, which only records from Kafka.consume can")

/** Kafka's word on what leaves a producer unusable, which no abort can follow: its only way on is to close. */
private fun Exception.abortable(): Boolean =
    this !is ProducerFencedException && this !is OutOfOrderSequenceException && this !is AuthorizationException

private fun <T> Future<T>.awaited(): T =
    try {
        get()
    } catch (failed: ExecutionException) {
        throw failed.cause ?: failed
    }

/** Enough records for one transaction to be worth its round trips. */
const val DEFAULT_BATCH: Int = 500

/** How long a transaction waits for its batch to fill, which is how late a quiet topic's last records are. */
val DEFAULT_WITHIN: Duration = 100.milliseconds
