package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.PekkoStreams
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.take
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One description of a consumer, run on whichever backend the run names, against one broker. */
class ConsumeTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-kafka-consume-test")

        private const val GENEROUS_SECONDS = 60L
    }

    /** By name, so JUnit names each case; the actor system exists only once the class has started. */
    private fun backend(name: String): StreamBackend =
        when (name) {
            "Forks" -> Forks()
            else -> PekkoStreams(pekko.system)
        }

    private fun properties(group: String): Map<String, Any> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
        ConsumerConfig.GROUP_ID_CONFIG to group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
    )

    private fun strings(group: String, topic: String) =
        Kafka.consume(properties(group), Topic(topic), key = StringDeserializer(), value = StringDeserializer())

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `the records a run handles are committed by the time it ends, and the group starts after them`(on: String) {
        val topic = "orders-$on"
        kafka.send(topic, "1", "2", "3", "4", "5")

        strings("placing-$on", topic).take(5).runCommitting().run(backend(on)).settled() shouldBe Exit.Done(5L)

        withClue("the exit completes after the consumer committed and closed") {
            kafka.committed("placing-$on", topic) shouldBe 5L
        }

        kafka.send(topic, "6")
        val seen = ConcurrentLinkedQueue<String>()
        strings("placing-$on", topic)
            .mapRecord { it.value().also(seen::add) }
            .take(1)
            .runCommitting()
            .run(backend(on))
            .settled() shouldBe Exit.Done(1L)
        seen.toList() shouldBe listOf("6")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a stop while the consumer waits on a quiet topic wakes it, and what was handled is committed`(on: String) {
        val topic = "quiet-$on"
        kafka.send(topic, "only")
        val handled = CountDownLatch(1)

        val running = strings("stopping-$on", topic)
            .mapRecord { it.value().also { handled.countDown() } }
            .runCommitting()
            .start(backend(on))
        handled.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

        running.stop()

        running.exit.settled() shouldBe Exit.Done(1L)
        kafka.committed("stopping-$on", topic) shouldBe 1L
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a record that fails to decode is a Left, and is still committed`(on: String) {
        val topic = "mixed-$on"
        kafka.send(topic, "fine", "bad")
        val strict = Deserializer { _, data: ByteArray ->
            String(data).also { if (it == "bad") throw SerializationException("unreadable") }
        }
        val seen = ConcurrentLinkedQueue<Any>()

        Kafka.consume(
            properties("decoding-$on"),
            Topic(topic),
            key = Decoder.string(),
            value = Decoder(strict) { false },
        )
            .mapRecord { decoded -> decoded.fold({ it }, { it.value() }).also(seen::add) }
            .take(2)
            .runCommitting()
            .run(backend(on))
            .settled() shouldBe Exit.Done(2L)

        seen.first() shouldBe "fine"
        seen.last().shouldBeInstanceOf<DecodeError>().offset shouldBe 1L
        kafka.committed("decoding-$on", topic) shouldBe 2L
    }
}
