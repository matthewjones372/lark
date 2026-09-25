package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.take
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.Deserializer
import org.apache.pekko.Done
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerSettings
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A record that failed to decode is routed or ends the run, and its offset moves on only once that is done. */
class RoutingTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-kafka-routing-test")

        private const val GENEROUS_SECONDS = 60L
    }

    private object Strict : Deserializer<String> {
        override fun deserialize(topic: String, data: ByteArray): String =
            String(data).also { if (it.startsWith("bad")) throw SerializationException("unreadable") }
    }

    private fun decoded(group: String, topic: String) =
        Kafka.subscribe(
            ConsumerSettings.create(pekko.system, ByteArrayDeserializer(), ByteArrayDeserializer())
                .withBootstrapServers(kafka.bootstrap)
                .withGroupId(group)
                .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
            Topic(topic),
            key = Decoder.string(),
            value = Decoder(Strict) { false },
        )

    private val committer: CommitterSettings get() = CommitterSettings.create(pekko.system)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    @Test
    fun `a diverted record is committed only after its function returns`() {
        kafka.send("diverting", "fine", "bad", "fine again")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dead = ConcurrentLinkedQueue<Long>()

        val running = decoded("diverters", "diverting")
            .divertLefts { error ->
                entered.countDown()
                release.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                dead.add(error.offset)
            }
            .take(2)
            .runCommitting(committer)
            .start(pekko.system)
        entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

        withClue("while the dead letter is being written, nothing past it is committed") {
            (kafka.committed("diverters", "diverting") ?: 0L) shouldBeLessThanOrEqual 1L
        }
        release.countDown()

        running.exit.settled() shouldBe Exit.Done(Done.getInstance())
        dead.toList() shouldBe listOf(1L)
        kafka.committed("diverters", "diverting") shouldBe 3L
    }

    @Test
    fun `a divert that throws leaves its record uncommitted`() {
        kafka.send("undeliverable", "fine", "bad")

        decoded("stranded", "undeliverable")
            .divertLefts { throw IllegalStateException("dead letters are down") }
            .runCommitting(committer)
            .run(pekko.system)
            .settled()
            .shouldBeInstanceOf<Exit.Died>()

        kafka.committed("stranded", "undeliverable") shouldBe 1L
    }

    @Test
    fun `absolve ends the run with the first record that failed to decode`() {
        kafka.send("strict", "fine", "bad")

        val exit = decoded("absolving", "strict")
            .absolve()
            .runCommitting(committer)
            .run(pekko.system)
            .settled()

        exit.shouldBeInstanceOf<Exit.Failed<DecodeError>>()
        exit.error.offset shouldBe 1L
        kafka.committed("absolving", "strict") shouldBe 1L
    }
}
