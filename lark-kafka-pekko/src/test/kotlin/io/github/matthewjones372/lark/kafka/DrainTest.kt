package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.start
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.pekko.Done
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A committing run stopped drains: the consumer stops fetching, and what is in flight finishes and commits. */
class DrainTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-kafka-drain-test")

        private const val GENEROUS_SECONDS = 60L
    }

    @Test
    fun `a stop while a body is running still commits that body's record, and close waits for the commit`() {
        kafka.send("slow", "1", "2")
        val second = CountDownLatch(1)
        val release = CountDownLatch(1)

        val running = Kafka.subscribe(
            ConsumerSettings.create(pekko.system, StringDeserializer(), StringDeserializer())
                .withBootstrapServers(kafka.bootstrap)
                .withGroupId("draining")
                .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
            Topic("slow"),
        )
            .mapParRecord(1) { record ->
                if (record.value() == "2") {
                    second.countDown()
                    release.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                }
                record.value()
            }
            .runCommitting(CommitterSettings.create(pekko.system))
            .start(pekko.system)
        second.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

        running.stop()
        release.countDown()
        running.close()

        running.exit.toCompletableFuture().getNow(null) shouldBe Exit.Done(Done.getInstance())
        withClue("the kill switch would have dropped record 2 between the stages; a drain commits it") {
            kafka.committed("draining", "slow") shouldBe 2L
        }
    }
}
