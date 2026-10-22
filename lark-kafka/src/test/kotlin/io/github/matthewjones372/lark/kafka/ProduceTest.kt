package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.capturingMetrics
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.PekkoStreams
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.of
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runCollect
import io.github.matthewjones372.lark.stream.take
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** One description of a producer, run on whichever backend the run names, against one broker. */
class ProduceTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-kafka-produce-test")

        private const val GENEROUS_SECONDS = 60L

        /** Small enough that one record in a test is refused outright, which the producer does not retry. */
        private const val MAX_REQUEST_BYTES = 1024
    }

    private fun backend(name: String): StreamBackend =
        when (name) {
            "Forks" -> Forks()
            else -> PekkoStreams(pekko.system)
        }

    private fun consumerProperties(group: String): Map<String, Any> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
        ConsumerConfig.GROUP_ID_CONFIG to group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
    )

    private fun producerProperties(): Map<String, Any> = mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
        ProducerConfig.MAX_REQUEST_SIZE_CONFIG to MAX_REQUEST_BYTES,
    )

    private fun consumed(group: String, topic: String) =
        Kafka.consume(consumerProperties(group), Topic(topic), key = StringDeserializer(), value = StringDeserializer())

    private fun strings() = Kafka.producer(producerProperties(), StringSerializer(), StringSerializer())

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `publishTo writes every element in order, and passes each on once the broker has it`(on: String) {
        val topic = Topic("written-$on")

        strings().use { producer ->
            Stream.of("a", "b", "c", "d", "e")
                .publishTo(producer) { letter -> topic.record(letter, letter.uppercase()) }
                .runCollect()
                .run(backend(on))
                .settled() shouldBe Exit.Done(listOf("a", "b", "c", "d", "e"))
        }

        kafka.read(topic.name, 5).map { it.key() to it.value() } shouldBe
            listOf("a" to "A", "b" to "B", "c" to "C", "d" to "D", "e" to "E")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a consumed record is committed once what it produced is acknowledged`(on: String) {
        val input = "orders-in-$on"
        val output = Topic("orders-out-$on")
        kafka.send(input, "1", "2", "3")

        strings().use { producer ->
            consumed("relaying-$on", input)
                .mapRecord { record -> record.value() }
                .publishRecord(producer) { order -> output.record(order, "placed $order") }
                .take(3)
                .runCommitting()
                .run(backend(on))
                .settled() shouldBe Exit.Done(3L)
        }

        kafka.read(output.name, 3).map { it.value() } shouldBe listOf("placed 1", "placed 2", "placed 3")
        kafka.committed("relaying-$on", input) shouldBe 3L
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a record the producer gives up on is a defect, and nothing from it on is committed`(on: String) {
        val input = "oversized-in-$on"
        kafka.send(input, "small", "x".repeat(MAX_REQUEST_BYTES * 2), "small again")

        val exit = strings().use { producer ->
            consumed("oversized-$on", input)
                .mapRecord { record -> record.value() }
                .publishRecord(producer, inFlight = 1) { value -> Topic("oversized-out-$on").record(value, value) }
                .runCommitting()
                .run(backend(on))
                .settled()
        }

        exit.shouldBeInstanceOf<Exit.Died>()
        kafka.committed("oversized-$on", input) shouldBe 1L
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a dead letter is written as it was read, with where it came from, before its offset moves on`(on: String) {
        val input = "letters-in-$on"
        val dead = Topic("letters-dead-$on")
        kafka.send(input, "fine", "bad", "fine again")
        val strict = Deserializer { _, data: ByteArray ->
            String(data).also { if (it == "bad") throw SerializationException("unreadable") }
        }

        val captured = capturingMetrics { captured ->
            Kafka.producer(producerProperties(), ByteArraySerializer(), ByteArraySerializer()).use { bytes ->
                Kafka.consume(
                    consumerProperties("letters-$on"),
                    Topic(input),
                    key = Decoder.string(),
                    value = Decoder(strict) { false },
                )
                    .divertLefts(bytes.deadLetters(dead))
                    .take(2)
                    .runCommitting()
                    .run(backend(on))
                    .settled() shouldBe Exit.Done(2L)
            }
            captured
        }
        captured.counter("lark.kafka.dead.letters") shouldBe 1.0
        captured.tags("lark.kafka.dead.letters")["topic"] shouldBe input

        val letter = kafka.read(dead.name, 1).single()
        letter.value() shouldBe "bad"
        fun header(name: String) = letter.headers().lastHeader("$DEAD_LETTER_HEADER.$name")?.value()?.let(::String)
        header("topic") shouldBe input
        header("offset") shouldBe "1"
        header("part") shouldBe "Value"
        kafka.committed("letters-$on", input) shouldBe 3L
    }

    @Test
    fun `publishAll answers each record in order, and omits what came after one the producer gave up on`() {
        val topic = Topic("published-all")
        val big = "x".repeat(MAX_REQUEST_BYTES * 2)

        val outcomes = strings().use { producer ->
            producer.publishAll(listOf("a", "b", big, "d").map { value -> topic.record(value, value) })
        }

        outcomes.take(2).map { it.getOrNull() } shouldBe
            listOf(Published(topic.name, 0, 0L), Published(topic.name, 0, 1L))
        val (failed, omitted) = outcomes.drop(2).map { it.leftOrNull()!! }
        failed.cause.shouldBeInstanceOf<RecordTooLargeException>()
        withClue("the record after the failure is not sent, and says which failure stopped it") {
            omitted.record.value() shouldBe "d"
            omitted.cause.shouldBeInstanceOf<PublishOmitted>().cause shouldBe failed.cause
        }
        kafka.read(topic.name, 2).map { it.value() } shouldBe listOf("a", "b")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a batch whose write fails ends on absolve, with only what came before the failure committed`(on: String) {
        val input = "batch-failing-in-$on"
        val output = Topic("batch-failing-out-$on")
        kafka.send(input, "1", "2", "x".repeat(MAX_REQUEST_BYTES * 2), "4", "5")

        val exit = strings().use { producer ->
            consumed("batch-failing-$on", input)
                .mapRecord { record -> record.value() }
                .publishBatch(producer, batch = 5, within = 10.seconds) { value -> output.record(value, value) }
                .absolve()
                .runCommitting()
                .run(backend(on))
                .settled()
        }

        exit.shouldBeInstanceOf<Exit.Failed<PublishFailed>>().error.cause.shouldBeInstanceOf<RecordTooLargeException>()
        kafka.read(output.name, 2).map { it.value() } shouldBe listOf("1", "2")
        kafka.committed("batch-failing-$on", input) shouldBe 2L
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `divertLefts hands on a batch's failed and omitted records, and the next batch commits past them`(on: String) {
        val input = "batch-diverted-in-$on"
        kafka.send(input, "1", "2", "x".repeat(MAX_REQUEST_BYTES * 2), "4", "5", "6")
        val lefts = ConcurrentLinkedQueue<PublishFailed>()

        strings().use { producer ->
            consumed("batch-diverted-$on", input)
                .mapRecord { record -> record.value() }
                .publishBatch(producer, batch = 5, within = 1.seconds) { value ->
                    Topic("batch-diverted-out-$on").record(value, value)
                }
                .divertLefts(lefts::add)
                .take(3)
                .runCommitting()
                .run(backend(on))
                .settled() shouldBe Exit.Done(3L)
        }

        lefts.map { it.record.value() to it.cause::class } shouldBe listOf(
            "x".repeat(MAX_REQUEST_BYTES * 2) to RecordTooLargeException::class,
            "4" to PublishOmitted::class,
            "5" to PublishOmitted::class,
        )
        withClue("a Left commits with the next Right on its partition, here the second batch's one record") {
            kafka.committed("batch-diverted-$on", input) shouldBe 6L
        }
    }

    @Test
    fun `publish answers where the record went, or why the producer gave up on it`() {
        val topic = Topic("published")

        val captured = capturingMetrics { captured ->
            strings().use { producer ->
                producer.publish(topic.record("k", "v")).getOrNull() shouldBe Published(topic.name, 0, 0L)
                producer.publish(topic.record("k", "x".repeat(MAX_REQUEST_BYTES * 2))).leftOrNull()!!
                    .cause.shouldBeInstanceOf<RecordTooLargeException>()
            }
            captured
        }

        captured.counter("lark.kafka.producer.records") shouldBe 1.0
        withClue("the acknowledged record's key and value") {
            captured.counter("lark.kafka.producer.bytes") shouldBe 2.0
        }
        captured.counter("lark.kafka.producer.failures") shouldBe 1.0
        captured.histogram("lark.kafka.producer.ack").size shouldBe 1
        captured.tags("lark.kafka.producer.records")["topic"] shouldBe topic.name
    }
}
