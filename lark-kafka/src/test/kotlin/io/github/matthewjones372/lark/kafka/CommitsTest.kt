package io.github.matthewjones372.lark.kafka

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The one commit path every consumer loop shares, at the moment its consumer is closed. */
@OptIn(KafkaSpi::class)
class CommitsTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        private const val GENEROUS_SECONDS = 30L
        private const val WAIT_MILLIS = 500L
        private const val HELD_FETCH_MILLIS = 3000
        private const val CLOSE_WITHIN_MILLIS = 1000L
        private const val POLL_MILLIS = 50L
    }

    private fun consumer(group: String) = KafkaConsumer(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        StringDeserializer(),
    )

    private fun KafkaConsumer<String, String>.polled(count: Int): List<ConsumerRecord<String, String>> {
        val read = ArrayList<ConsumerRecord<String, String>>()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS)
        while (read.size < count && System.nanoTime() < until) read += poll(Duration.ofMillis(100))
        return read
    }

    @Test
    fun `a close on an interrupted thread still commits what was handled, and leaves the thread interrupted`() {
        val topic = "closed-interrupted"
        kafka.send(topic, "1", "2", "3")
        val consumer = consumer(topic).also { it.subscribe(listOf(topic)) }
        val commits = Commits(consumer)
        consumer.polled(3).forEach { record -> commits.carrying(record).handle?.handled() }

        // A take that ends a Forks run interrupts the fork reading the source, which then closes it.
        val stillInterrupted = CompletableFuture.supplyAsync {
            Thread.currentThread().interrupt()
            commits.close()
            Thread.currentThread().isInterrupted
        }.get(GENEROUS_SECONDS, TimeUnit.SECONDS)

        withClue("the close committed all three records, though its thread was interrupted") {
            kafka.committed(topic, topic) shouldBe 3L
        }
        withClue("the interrupt is still there for whoever sent it") { stillInterrupted shouldBe true }
    }

    private fun producer(id: String) = KafkaProducer(
        mapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
            ProducerConfig.TRANSACTIONAL_ID_CONFIG to id,
        ),
        StringSerializer(),
        StringSerializer(),
    ).also { it.initTransactions() }

    /** The offset after [records], as a transaction over them commits it. */
    private fun after(records: List<ConsumerRecord<String, String>>) =
        records.groupBy { TopicPartition(it.topic(), it.partition()) }
            .mapValues { (_, read) -> OffsetAndMetadata(read.maxOf { it.offset() } + 1) }

    @Test
    fun `a close waits for a transaction sending the member's offsets, which the broker takes from the member`() {
        val topic = "leaving-mid-send"
        kafka.send(topic, "1", "2", "3")
        val consumer = consumer(topic).also { it.subscribe(listOf(topic)) }
        val commits = Commits(consumer)
        val records = consumer.polled(3).also { commits.polled() }
        val handle = commits.carrying(records.last()).handle!!
        val sending = CountDownLatch(1)
        val send = CountDownLatch(1)

        producer("leaving-mid-send").use { producer ->
            producer.beginTransaction()
            val generation = CompletableFuture.supplyAsync {
                handle.withGroup { group ->
                    sending.countDown()
                    send.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                    producer.sendOffsetsToTransaction(after(records), group!!)
                    group.generationId()
                }
            }
            sending.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
            val closing = CompletableFuture.runAsync(commits::close)

            withClue("the consumer does not leave while a transaction is sending offsets as its member") {
                shouldThrow<TimeoutException> { closing.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
            }
            send.countDown()
            withClue("the offsets went as the member, which was still in its group") {
                (generation.get(GENEROUS_SECONDS, TimeUnit.SECONDS) > 0) shouldBe true
            }
            producer.commitTransaction()
            closing.get(GENEROUS_SECONDS, TimeUnit.SECONDS)
        }

        kafka.committed(topic, topic) shouldBe 3L
    }

    @Test
    fun `a transaction after the close sends the group alone, which the broker takes`() {
        val topic = "sent-after-leaving"
        kafka.send(topic, "1", "2", "3")
        val consumer = consumer(topic).also { it.subscribe(listOf(topic)) }
        val commits = Commits(consumer)
        val records = consumer.polled(3).also { commits.polled() }
        val handle = commits.carrying(records.last()).handle!!
        commits.close()

        producer("sent-after-leaving").use { producer ->
            producer.beginTransaction()
            handle.withGroup { group ->
                withClue("the member has gone, so no generation goes with the offsets") {
                    group!!.generationId() shouldBe -1
                }
                producer.sendOffsetsToTransaction(after(records), group)
            }
            producer.commitTransaction()
        }

        kafka.committed(topic, topic) shouldBe 3L
    }

    @Test
    fun `a record handled after its partition was revoked commits nothing, and one read after it is back does`() {
        val topic = "handled-after-revoke"
        kafka.send(topic, "1", "2", "3")
        val consumer = consumer(topic).also { it.subscribe(listOf(topic)) }
        val commits = Commits(consumer)
        val records = consumer.polled(3)
        val late = records.map { commits.carrying(it).handle!! }
        val partition = TopicPartition(topic, 0)

        // What a rebalance does to a partition this consumer loses, while its records are still in the stream.
        commits.forget(listOf(partition))
        late.forEach { it.handled() }
        commits.commit()
        withClue("records read before the revoke are the new owner's to commit") {
            kafka.committed(topic, topic) shouldBe null
        }

        commits.carrying(records.last()).handle!!.handled()
        commits.close()
        withClue("a record read once the partition is this consumer's again commits as usual") {
            kafka.committed(topic, topic) shouldBe 3L
        }
    }

    @Test
    fun `a close does not wait out the fetch the broker is holding, and still leaves the group with its offsets`() {
        val topic = "closed-past-a-held-fetch"
        kafka.send(topic, "1", "2", "3")
        // A broker that holds an empty fetch for 3s, so a close that waits for it is plain to see.
        val consumer = KafkaConsumer(
            mapOf<String, Any>(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG to topic,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG to HELD_FETCH_MILLIS,
            ),
            StringDeserializer(),
            StringDeserializer(),
        ).also { it.subscribe(listOf(topic)) }
        val commits = Commits(consumer)
        consumer.polled(3).forEach { record -> commits.carrying(record).handle?.handled() }
        // The read ahead that a take or a stop interrupts: its fetch is at the broker, held.
        consumer.poll(Duration.ofMillis(POLL_MILLIS))

        val started = System.nanoTime()
        commits.close()
        val closing = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        withClue("the close took ${closing}ms against a fetch held for ${HELD_FETCH_MILLIS}ms") {
            (closing < CLOSE_WITHIN_MILLIS) shouldBe true
        }
        kafka.committed(topic, topic) shouldBe 3L
        withClue("the consumer left its group rather than timing out of it") {
            kafka.members(topic) shouldBe 0
        }
    }
}
