@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import arrow.core.Either
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.mapAsync
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.serialization.Serializer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import org.apache.kafka.clients.producer.Producer as Client

/**
 * One `KafkaProducer`, shared by every run and every caller, which is how the client is meant to be used: it is
 * thread-safe, and it batches what many callers send. Whoever opens it closes it, which sends what is pending.
 *
 * A send the client can retry, it retries, for up to `delivery.timeout.ms`. What reaches lark is final: a record
 * the broker acknowledged, or one the producer gave up on.
 */
class Producer<K, V> @KafkaSpi constructor(private val client: Client<K, V>) : AutoCloseable {

    /** The record handed to the producer; the stage completes once the broker acknowledges it. */
    fun send(record: ProducerRecord<K, V>): CompletableFuture<Published> {
        val acked = CompletableFuture<Published>()
        // A serializer that throws does so here rather than in the callback, and a full buffer waits here too.
        Either.catch {
            client.send(record) { metadata, failed ->
                if (failed == null) acked.complete(metadata.published()) else acked.completeExceptionally(failed)
            }
        }.onLeft(acked::completeExceptionally)
        return acked
    }

    /** [send], waited on: `Left` for a record the producer gave up on. */
    fun publish(record: ProducerRecord<K, V>): Either<PublishFailed, Published> =
        Either.catch { send(record).get() }
            .mapLeft { thrown -> PublishFailed(record, (thrown as? ExecutionException)?.cause ?: thrown) }

    override fun close() = client.close()
}

/** A producer from [properties], each record's key and value written by [key] and [value]. */
fun <K, V> Kafka.producer(properties: Map<String, Any>, key: Serializer<K>, value: Serializer<V>): Producer<K, V> =
    Producer(KafkaProducer(properties, key, value))

/** Where the broker put a record. */
data class Published(val topic: String, val partition: Int, val offset: Long)

/** A record the producer gave up on, and why: too large, not authorised, or not acknowledged in time. */
class PublishFailed internal constructor(val record: ProducerRecord<*, *>, val cause: Throwable) {
    override fun toString(): String = "PublishFailed(${record.topic()}: $cause)"
}

/** A record for this topic, so a sink's arguments read as the topic they write to. */
fun <K, V> Topic.record(key: K, value: V): ProducerRecord<K, V> = ProducerRecord(name, key, value)

/**
 * Each element sent as the record [to] makes of it, and passed on, in order, once the broker has it. Up to
 * [inFlight] wait on the broker at once, so the producer can batch them. A record the producer gives up on is a
 * defect, for `restartOnDefect`.
 */
fun <E, A : Any, K, V> Stream<E, A>.publishTo(
    producer: Producer<K, V>,
    inFlight: Int = DEFAULT_IN_FLIGHT,
    to: (A) -> ProducerRecord<K, V>,
): Stream<E, A> =
    mapAsync(inFlight) { element -> producer.send(to(element)).thenApply { element } }

/**
 * [publishTo] for a consumed record: its offset moves on only once what it produced is acknowledged, and every
 * record before it has been too. A stop or a defect leaves the rest uncommitted, and they are sent again.
 */
fun <E, A : Any, K, V> Stream<E, Committed<A>>.publishRecord(
    producer: Producer<K, V>,
    inFlight: Int = DEFAULT_IN_FLIGHT,
    to: (A) -> ProducerRecord<K, V>,
): Stream<E, Committed<A>> =
    mapAsync(inFlight) { c: Committed<A> -> producer.send(c.annotated(to)).thenApply { c } }

/**
 * Each record that could not be read, written to [topic] as it was read, for `divertLefts`: its key, value and
 * headers, and headers saying where it came from and why. It returns once the broker has it, so the bad record's
 * offset moves on only then; a dead letter the producer gives up on is thrown, a defect.
 */
fun Producer<ByteArray?, ByteArray?>.deadLetters(topic: Topic): (DecodeError) -> Unit = { error ->
    val letter = ProducerRecord(topic.name, null, error.key, error.value, error.headers)
    mapOf(
        "topic" to error.topic,
        "partition" to error.partition.toString(),
        "offset" to error.offset.toString(),
        "part" to error.part.name,
        "cause" to error.cause.toString(),
    ).forEach { (name, value) -> letter.headers().add("$DEAD_LETTER_HEADER.$name", value.toByteArray()) }
    publish(letter).onLeft { failed -> throw failed.cause }
}

/** Enough in flight for the producer to fill its batches, few enough that a stop leaves little to send again. */
const val DEFAULT_IN_FLIGHT: Int = 256

/** What every header a dead letter gains is named under. */
const val DEAD_LETTER_HEADER: String = "lark.dead-letter"

private fun RecordMetadata.published() = Published(topic(), partition(), offset())
