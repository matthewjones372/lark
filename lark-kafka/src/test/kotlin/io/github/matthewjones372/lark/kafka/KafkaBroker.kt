package io.github.matthewjones372.lark.kafka

import io.github.embeddedkafka.EmbeddedK
import io.github.embeddedkafka.EmbeddedKafka
import io.github.embeddedkafka.EmbeddedKafkaConfig
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec
import org.apache.kafka.clients.admin.ListOffsetsOptions
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.IsolationLevel
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.utils.Exit
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.net.BindException
import java.net.ServerSocket
import java.time.Duration
import java.util.concurrent.TimeUnit

/** A broker in the test JVM for one test class, started and stopped by JUnit 5 as the actor system is. */
class KafkaBroker : BeforeAllCallback, AfterAllCallback {

    private lateinit var kafka: EmbeddedK

    val bootstrap: String get() = "localhost:${kafka.config().kafkaPort()}"

    override fun beforeAll(context: ExtensionContext) {
        // A fatal fault in the broker exits the JVM through Kafka's Exit, which ends the whole test run with no
        // report; thrown instead, it fails the test that is running, and slf4j-simple has logged the fault.
        Exit.setExitProcedure { code, _ -> error("the broker asked the JVM to exit with $code") }
        Exit.setHaltProcedure { code, _ -> error("the broker asked the JVM to halt with $code") }
        kafka = start(attempt = 1)
    }

    // A free port is free only until another test JVM takes it, before the broker binds it; then try new ones. The
    // broker wraps the BindException in whatever it was starting, so the catch is wide and the cause chain decides.
    @Suppress("TooGenericExceptionCaught")
    private fun start(attempt: Int): EmbeddedK {
        val config = EmbeddedKafkaConfig.apply(
            freePort(),
            freePort(),
            // A group's first member is not kept waiting for others to join, which is 3s a test by default.
            brokerProperties(),
            EmbeddedKafkaConfig.`apply$default$4`(),
            EmbeddedKafkaConfig.`apply$default$5`(),
        )
        return try {
            EmbeddedKafka.start(config)
        } catch (e: Exception) {
            if (attempt < ATTEMPTS && bindFailed(e)) start(attempt + 1) else throw e
        }
    }

    private fun bindFailed(t: Throwable): Boolean =
        generateSequence(t) { it.cause }.any { it is BindException }

    override fun afterAll(context: ExtensionContext) {
        try {
            kafka.stop(true)
        } finally {
            Exit.resetExitProcedure()
            Exit.resetHaltProcedure()
        }
    }

    fun send(topic: String, vararg values: String) {
        KafkaProducer(mapOf<String, Any>("bootstrap.servers" to bootstrap), StringSerializer(), StringSerializer())
            .use { producer ->
                values.forEach { value ->
                    producer.send(ProducerRecord(topic, value)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
    }

    /** The first [count] records on [topic], read from the start by a consumer of no group. */
    fun read(topic: String, count: Int): List<ConsumerRecord<String?, String?>> =
        KafkaConsumer(
            mapOf<String, Any>(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap),
            StringDeserializer(),
            StringDeserializer(),
        ).use { consumer ->
            val partition = TopicPartition(topic, 0)
            consumer.assign(listOf(partition))
            consumer.seekToBeginning(listOf(partition))
            val read = mutableListOf<ConsumerRecord<String?, String?>>()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (read.size < count && System.nanoTime() < until) {
                read += consumer.poll(Duration.ofMillis(POLL_MILLIS))
            }
            read.take(count)
        }

    /** [topic] made with [partitions] partitions, before anything is sent to it. */
    fun create(topic: String, partitions: Int) {
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
            admin.createTopics(listOf(NewTopic(topic, partitions, 1))).all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    /** [values] sent to [partition] of [topic], in order. */
    fun sendTo(topic: String, partition: Int, vararg values: String) {
        KafkaProducer(mapOf<String, Any>("bootstrap.servers" to bootstrap), StringSerializer(), StringSerializer())
            .use { producer ->
                values.forEach { value ->
                    producer.send(ProducerRecord(topic, partition, null, value)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            }
    }

    /**
     * Every record on [topic]'s only partition up to its end, as a reader at [isolation] sees it: with
     * `read_committed` the end is the last committed transaction's, and an aborted one's records are skipped.
     * Read once no transaction is open on it, so a transaction that has just ended is read as it ended.
     */
    fun readAll(topic: String, isolation: String): List<ConsumerRecord<String?, String?>> {
        settled(TopicPartition(topic, 0))
        return KafkaConsumer(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
                ConsumerConfig.ISOLATION_LEVEL_CONFIG to isolation,
            ),
            StringDeserializer(),
            StringDeserializer(),
        ).use { consumer ->
            val partition = TopicPartition(topic, 0)
            consumer.assign(listOf(partition))
            consumer.seekToBeginning(listOf(partition))
            val end = consumer.endOffsets(listOf(partition)).getValue(partition)
            val read = mutableListOf<ConsumerRecord<String?, String?>>()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (consumer.position(partition) < end && System.nanoTime() < until) {
                read += consumer.poll(Duration.ofMillis(POLL_MILLIS))
            }
            read
        }
    }

    // commitTransaction returns once the coordinator has prepared the commit; the marker that ends the
    // transaction on the partition is written after, so until then a read_committed reader stops short of it.
    private fun settled(partition: TopicPartition) {
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
            fun end(isolation: IsolationLevel): Long =
                admin.listOffsets(mapOf(partition to OffsetSpec.latest()), ListOffsetsOptions(isolation))
                    .all().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getValue(partition).offset()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            while (end(IsolationLevel.READ_COMMITTED) < end(IsolationLevel.READ_UNCOMMITTED)) {
                check(System.nanoTime() < until) {
                    "a transaction on $partition was still open after ${TIMEOUT_SECONDS}s"
                }
                Thread.sleep(SETTLE_MILLIS)
            }
        }
    }

    /**
     * The offset the group has committed on the topic's only partition, or null for none, once no transaction is
     * still committing one: a transaction's offsets are pending until its marker lands, and read before then, the
     * group's last offset is the one before. Asked for by partition, a pending one comes back missing, not null.
     */
    fun committed(group: String, topic: String): Long? {
        val partition = TopicPartition(topic, 0)
        val asked = mapOf(group to ListConsumerGroupOffsetsSpec().topicPartitions(listOf(partition)))
        return Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
            generateSequence {
                admin.listConsumerGroupOffsets(asked, ListConsumerGroupOffsetsOptions().requireStable(true))
                    .partitionsToOffsetAndMetadata(group).get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }.first { offsets ->
                check(partition in offsets || System.nanoTime() < until) {
                    "$group's offset on $partition was still being committed after ${TIMEOUT_SECONDS}s"
                }
                (partition in offsets).also { stable -> if (!stable) Thread.sleep(SETTLE_MILLIS) }
            }[partition]?.offset()
        }
    }

    // Scala's `updated` widens its value type, which Kotlin reads as a Map of Any; the entry added is a String.
    @Suppress("UNCHECKED_CAST")
    private fun brokerProperties(): scala.collection.immutable.Map<String, String> =
        EmbeddedKafkaConfig.`apply$default$3`()
            .updated("group.initial.rebalance.delay.ms", "0")
            // One broker holds the transaction log, so a transaction can be committed at all.
            .updated("transaction.state.log.replication.factor", "1")
            .updated("transaction.state.log.min.isr", "1") as scala.collection.immutable.Map<String, String>

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val ATTEMPTS = 3
        const val POLL_MILLIS = 200L
        const val SETTLE_MILLIS = 5L
    }
}
