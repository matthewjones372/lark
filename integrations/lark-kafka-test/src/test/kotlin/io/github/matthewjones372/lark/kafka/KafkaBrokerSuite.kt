package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldThrow
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.utils.Exit

/** The test broker's own helpers, which every transactional claim in this module is read through. */
object KafkaBrokerSuite : Suite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    private const val COMMITS_AFTER_MILLIS = 500L

    init {
        test("readAll waits for a transaction still ending, and reads what it committed") { kafka ->
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

                val read = kafka.readAll(Topic("still-ending"), "read_committed").map { it.value() }
                committing.join()
                read shouldBe listOf("committed")
            }
        }

        test("a broker that asks the JVM to exit fails the test that is running instead") { _ ->
            // What a fatal fault in the broker does, through ProcessTerminatingFaultHandler.
            shouldThrow<IllegalStateException>({ Exit.exit(1) }) { thrown ->
                thrown.message shouldContain "asked the JVM to exit with 1"
            } and shouldThrow<IllegalStateException>({ Exit.halt(1) }) { thrown ->
                thrown.message shouldContain "asked the JVM to halt with 1"
            }
        }
    }
}
