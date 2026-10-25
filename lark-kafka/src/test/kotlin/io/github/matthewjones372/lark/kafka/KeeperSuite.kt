package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The keeper alone, over a consumer that never reaches a broker (spec 0157). */
object KeeperSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private const val SHORT_INTERVAL_MILLIS = 200
    private const val GENEROUS_SECONDS = 10L

    /** A consumer whose keeper poll fails, as one fenced or unauthorised would. */
    private class Failing : KafkaConsumer<String, String>(
        mapOf<String, Any>(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to "localhost:1"),
        StringDeserializer(),
        StringDeserializer(),
    ) {
        val polled = CountDownLatch(1)

        override fun poll(timeout: Duration): ConsumerRecords<String, String> {
            polled.countDown()
            throw KafkaException("refused")
        }
    }

    init {
        test("a close after the keeper's poll failed still runs, then throws what the poll threw") { _ ->
            Failing().use { consumer ->
                val properties = mapOf<String, Any>(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to SHORT_INTERVAL_MILLIS)
                val keeper = Keeper(consumer, properties, ConsumerMeters.none()) { }
                val polled = consumer.polled.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
                keeper.stop()
                var closed = false

                polled and shouldThrow<KafkaException>({ keeper.closing { closed = true } }) { thrown ->
                    val ranFirst = withClue("the commit and close ran before the keeper's failure was thrown") {
                        closed shouldBe true
                    }
                    ranFirst and (thrown.message shouldBe "refused")
                }
            }
        }

        test("an interval written with spaces around it is read as Kafka reads it") { _ ->
            Failing().use { consumer ->
                val properties =
                    mapOf<String, Any>(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to " $SHORT_INTERVAL_MILLIS ")
                val keeper = Keeper(consumer, properties, ConsumerMeters.none()) { }
                try {
                    withClue("the keeper polled within the short interval, not the five-minute default") {
                        consumer.polled.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
                    }
                } finally {
                    keeper.stop()
                }
            }
        }
    }
}
