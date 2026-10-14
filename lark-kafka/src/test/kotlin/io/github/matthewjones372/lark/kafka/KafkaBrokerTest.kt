package io.github.matthewjones372.lark.kafka

import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The test broker's own helpers, which every transactional claim in this module is read through. */
class KafkaBrokerTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        private const val COMMITS_AFTER_MILLIS = 500L
    }

    @Test
    fun `readAll waits for a transaction still ending, and reads what it committed`() {
        KafkaProducer(
            mapOf<String, Any>(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
                ProducerConfig.TRANSACTIONAL_ID_CONFIG to "still-ending",
            ),
            StringSerializer(),
            StringSerializer(),
        ).use { producer ->
            producer.initTransactions()
            producer.beginTransaction()
            producer.send(ProducerRecord("still-ending", "committed")).get()
            // Stands in for a commit marker the broker has yet to write, which a read straight after
            // commitTransaction can find missing: until it lands, read_committed stops before the transaction.
            val committing = Thread.ofVirtual().start {
                Thread.sleep(COMMITS_AFTER_MILLIS)
                producer.commitTransaction()
            }

            kafka.readAll("still-ending", "read_committed").map { it.value() } shouldBe listOf("committed")
            committing.join()
        }
    }
}
