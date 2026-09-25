package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.mapOrFail
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runCollect
import io.github.matthewjones372.lark.stream.take
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.pekko.Done
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/** A subscription ends on `runCommitting`, and what it commits is where the group starts next time. */
class SubscribeTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-kafka-subscribe-test")

        private const val GENEROUS_SECONDS = 60L
    }

    private data class Refused(val value: String)

    private fun consumer(group: String): ConsumerSettings<String, String> =
        ConsumerSettings.create(pekko.system, StringDeserializer(), StringDeserializer())
            .withBootstrapServers(kafka.bootstrap)
            .withGroupId(group)
            .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            // The connector keeps a stopped consumer in its group this long, and the next run in the
            // same group waits for it to leave. The default is 30s.
            .withStopTimeout(Duration.ofSeconds(1))

    private val committer: CommitterSettings get() = CommitterSettings.create(pekko.system)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    @Test
    fun `the records a run handles are committed, and the group starts after them next time`() {
        kafka.send("orders", "1", "2", "3", "4", "5")

        Kafka.subscribe(consumer("placing"), Topic("orders"))
            .take(5)
            .runCommitting(committer)
            .run(pekko.system)
            .settled() shouldBe Exit.Done(Done.getInstance())

        kafka.committed("placing", "orders") shouldBe 5L

        kafka.send("orders", "6")
        val next = Kafka.subscribe(consumer("placing"), Topic("orders"))
            .map { it.value.value() }
            .take(1)
            .runCollect()
            .run(pekko.system)
            .settled()

        withClue("a second run in the same group sees none of the five again") {
            next shouldBe Exit.Done(listOf("6"))
        }
    }

    @Test
    fun `a declared failure in the stream is the committing run's exit`() {
        kafka.send("refusals", "fine", "refused")

        Kafka.subscribe(consumer("refusing"), Topic("refusals"))
            .mapOrFail { record -> if (record.value.value() == "refused") fail(Refused("refused")) else record }
            .runCommitting(committer)
            .run(pekko.system)
            .settled() shouldBe Exit.Failed(Refused("refused"))
    }
}
