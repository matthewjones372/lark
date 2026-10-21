package io.github.matthewjones372.lark.kafka

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The keeper alone, over a consumer that never reaches a broker (spec 0157). */
class KeeperTest {

    companion object {
        private const val SHORT_INTERVAL_MILLIS = 200
        private const val GENEROUS_SECONDS = 10L
    }

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

    @Test
    fun `a close after the keeper's poll failed still runs, then throws what the poll threw`() {
        Failing().use { consumer ->
            val properties = mapOf<String, Any>(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to SHORT_INTERVAL_MILLIS)
            val keeper = Keeper(consumer, properties, ConsumerMeters.none()) { }
            consumer.polled.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
            keeper.stop()
            var closed = false

            val thrown = shouldThrow<KafkaException> { keeper.closing { closed = true } }

            withClue("the commit and close ran before the keeper's failure was thrown") { closed shouldBe true }
            thrown.message shouldBe "refused"
        }
    }

    @Test
    fun `an interval written with spaces around it is read as Kafka reads it`() {
        Failing().use { consumer ->
            val properties =
                mapOf<String, Any>(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to " $SHORT_INTERVAL_MILLIS ")
            val keeper = Keeper(consumer, properties) { }
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
