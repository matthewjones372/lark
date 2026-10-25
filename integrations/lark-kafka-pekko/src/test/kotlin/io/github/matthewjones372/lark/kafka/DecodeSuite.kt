package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.fail.fold
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.restartOnDefect
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.Deserializer
import org.apache.pekko.Done
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerSettings
import java.io.IOException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** A record that fails to decode is a value to route, and a decoder whose backend is down is a defect to restart on. */
object DecodeSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("lark-kafka-decode-test")

    private const val GENEROUS_SECONDS = 60L

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

    private fun bytes(kit: KafkaKit, group: String): ConsumerSettings<ByteArray?, ByteArray?> =
        ConsumerSettings.create(kit.system, ByteArrayDeserializer(), ByteArrayDeserializer())
            .withBootstrapServers(kit.broker.bootstrap)
            .withGroupId(group)
            .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")

    private fun committer(kit: KafkaKit): CommitterSettings = CommitterSettings.create(kit.system)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    init {
        test("a record that fails to decode is a Left with its position and raw bytes") { kit ->
            kit.broker.send(Topic("mixed"), "fine", "bad")
            val seen = ConcurrentLinkedQueue<Any>()

            val exit = Kafka.subscribe(
                bytes(kit, "reading"),
                Topic("mixed"),
                key = Decoder.string(),
                value = Decoder(Orders(), transient = ::registryDown),
            )
                .mapRecord { decoded -> decoded.fold({ it }, { it.value() }).also(seen::add) }
                .take(2)
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled()

            val (fine, bad) = seen.toList()
            (exit shouldBe Exit.Done(Done.getInstance())) and
                (fine shouldBe "fine") and
                bad.shouldBeInstanceOf<DecodeError> { error ->
                    (error.topic shouldBe "mixed") and
                        (error.partition shouldBe 0) and
                        (error.offset shouldBe 1L) and
                        (error.part shouldBe DecodeError.Part.Value) and
                        (error.value?.let(::String) shouldBe "bad") and
                        error.cause.shouldBeInstanceOf<SerializationException>()
                } and
                withClue("a record that decoded to a Left is still committed") {
                    kit.broker.committed("reading", Topic("mixed")) shouldBe 2L
                }
        }

        test("a throw the decoder calls transient ends the run Died") { kit ->
            kit.broker.send(Topic("unreachable"), "flaky")

            val exit = Kafka.subscribe(
                bytes(kit, "dying"),
                Topic("unreachable"),
                key = Decoder.string(),
                value = Decoder(Orders(downFor = Int.MAX_VALUE), transient = ::registryDown),
            )
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled()

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                generateSequence(died.cause) { it.cause }.map { it::class }.toList() shouldContain IOException::class
            }
        }

        test("after restartOnDefect the record whose decoder was down is read again") { kit ->
            kit.broker.send(Topic("recovering"), "flaky")
            val orders = Orders(downFor = 1)
            val seen = ConcurrentLinkedQueue<String>()

            val exit = Kafka.subscribe(
                bytes(kit, "restarting"),
                Topic("recovering"),
                key = Decoder.string(),
                value = Decoder(orders, ::registryDown),
            )
                .restartOnDefect(Schedule.recurs<Throwable>(1))
                .mapRecord { decoded -> decoded.fold({ "left" }, { it.value() }).also(seen::add) }
                .take(1)
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled()

            (exit shouldBe Exit.Done(Done.getInstance())) and
                (seen.toList() shouldBe listOf("flaky")) and
                (orders.asked.get() shouldBe 2)
        }
    }
}
