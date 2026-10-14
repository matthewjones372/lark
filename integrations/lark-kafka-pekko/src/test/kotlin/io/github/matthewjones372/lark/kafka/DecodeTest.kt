package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.restartOnDefect
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.take
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
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
import java.io.IOException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A record that fails to decode is a value to route, and a decoder whose backend is down is a defect to restart on. */
class DecodeTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-kafka-decode-test")

        private const val GENEROUS_SECONDS = 60L
    }

    /** "bad" is a record nobody can read; "flaky" is one whose backend is down the first [downFor] times. */
    private class Orders(private val downFor: Int = 0) : Deserializer<String> {
        val asked = AtomicInteger()

        override fun deserialize(topic: String, data: ByteArray): String {
            val text = String(data)
            if (text == "bad") throw SerializationException("unknown magic byte")
            if (text == "flaky" && asked.incrementAndGet() <= downFor) {
                throw SerializationException("registry unreachable", IOException("connection refused"))
            }
            return text
        }
    }

    private fun registryDown(t: Throwable): Boolean = generateSequence(t) { it.cause }.any { it is IOException }

    private fun bytes(group: String): ConsumerSettings<ByteArray?, ByteArray?> =
        ConsumerSettings.create(pekko.system, ByteArrayDeserializer(), ByteArrayDeserializer())
            .withBootstrapServers(kafka.bootstrap)
            .withGroupId(group)
            .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    private val committer: CommitterSettings get() = CommitterSettings.create(pekko.system)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    @Test
    fun `a record that fails to decode is a Left with its position and raw bytes`() {
        kafka.send("mixed", "fine", "bad")
        val seen = ConcurrentLinkedQueue<Any>()

        Kafka.subscribe(
            bytes("reading"),
            Topic("mixed"),
            key = Decoder.string(),
            value = Decoder(Orders(), transient = ::registryDown),
        )
            .mapRecord { decoded -> decoded.fold({ it }, { it.value() }).also(seen::add) }
            .take(2)
            .runCommitting(committer)
            .run(pekko.system)
            .settled() shouldBe Exit.Done(Done.getInstance())

        val (fine, bad) = seen.toList()
        fine shouldBe "fine"
        bad.shouldBeInstanceOf<DecodeError>()
        bad.topic shouldBe "mixed"
        bad.partition shouldBe 0
        bad.offset shouldBe 1L
        bad.part shouldBe DecodeError.Part.Value
        bad.value?.let(::String) shouldBe "bad"
        bad.cause.shouldBeInstanceOf<SerializationException>()
        withClue("a record that decoded to a Left is still committed") {
            kafka.committed("reading", "mixed") shouldBe 2L
        }
    }

    @Test
    fun `a throw the decoder calls transient ends the run Died`() {
        kafka.send("unreachable", "flaky")

        val exit = Kafka.subscribe(
            bytes("dying"),
            Topic("unreachable"),
            key = Decoder.string(),
            value = Decoder(Orders(downFor = Int.MAX_VALUE), transient = ::registryDown),
        )
            .runCommitting(committer)
            .run(pekko.system)
            .settled()

        exit.shouldBeInstanceOf<Exit.Died>()
        generateSequence(exit.cause) { it.cause }.map { it::class }.toList() shouldContain IOException::class
    }

    @Test
    fun `after restartOnDefect the record whose decoder was down is read again`() {
        kafka.send("recovering", "flaky")
        val orders = Orders(downFor = 1)
        val seen = ConcurrentLinkedQueue<String>()

        Kafka.subscribe(
            bytes("restarting"),
            Topic("recovering"),
            key = Decoder.string(),
            value = Decoder(orders, ::registryDown),
        )
            .restartOnDefect(Schedule.recurs<Throwable>(1))
            .mapRecord { decoded -> decoded.fold({ "left" }, { it.value() }).also(seen::add) }
            .take(1)
            .runCommitting(committer)
            .run(pekko.system)
            .settled() shouldBe Exit.Done(Done.getInstance())

        seen.toList() shouldBe listOf("flaky")
        orders.asked.get() shouldBe 2
    }
}
