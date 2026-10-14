package io.github.matthewjones372.lark.kafka

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The one commit path every consumer loop shares, at the moment its consumer is closed. */
@OptIn(KafkaSpi::class)
class CommitsTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        private const val GENEROUS_SECONDS = 30L
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
}
