package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.PekkoStreams
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.map
import io.github.matthewjones372.lark.stream.of
import io.github.matthewjones372.lark.stream.restartOnDefect
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.runFold
import io.github.matthewjones372.lark.stream.take
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A record's outputs and its offset in one transaction, on whichever backend the run names. */
class TransactTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-kafka-transact-test")

        private const val GENEROUS_SECONDS = 60L
        private const val COMMITTED = "read_committed"
        private const val EVERYTHING = "read_uncommitted"
    }

    private fun backend(name: String): StreamBackend =
        when (name) {
            "Forks" -> Forks()
            else -> PekkoStreams(pekko.system)
        }

    private fun consumed(group: String, topic: String) =
        Kafka.consume(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG to group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
            Topic(topic),
            key = StringDeserializer(),
            value = StringDeserializer(),
        )

    private fun producer(id: String, value: Serializer<String> = StringSerializer()) =
        Kafka.transactionalProducer(
            mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap),
            transactionalId = id,
            key = StringSerializer(),
            value = value,
        )

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a record's outputs and its offset are committed together`(on: String) {
        val input = "carts-$on"
        val output = Topic("orders-$on")
        kafka.send(input, "1", "2", "3")

        producer("checkout-$on").use { orders ->
            consumed("checkout-$on", input)
                .mapRecord { record -> record.value() }
                .take(3)
                .runTransactionally(orders) { cart -> listOf(output.record(cart, "order $cart")) }
                .run(backend(on))
                .settled() shouldBe Exit.Done(3L)
        }

        kafka.readAll(output.name, COMMITTED).map { it.value() } shouldBe listOf("order 1", "order 2", "order 3")
        withClue("the transaction committed the group's offsets; the consumer committed none of its own") {
            kafka.committed("checkout-$on", input) shouldBe 3L
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a transaction that fails is aborted, and neither its outputs nor its offsets are seen`(on: String) {
        val input = "failing-carts-$on"
        val output = Topic("failing-orders-$on")
        kafka.send(input, "1", "2", "boom")
        val refusing = Serializer<String> { _, value ->
            if (value == "boom") throw SerializationException("unwritable") else value.toByteArray()
        }

        val exit = producer("failing-$on", refusing).use { orders ->
            consumed("failing-$on", input)
                .mapRecord { record -> record.value() }
                .runTransactionally(orders, within = 5.seconds) { cart -> listOf(output.record(cart, cart)) }
                .run(backend(on))
                .settled()
        }

        exit.shouldBeInstanceOf<Exit.Died>()
        withClue("the two records sent before the failure were aborted with it") {
            kafka.readAll(output.name, COMMITTED) shouldBe emptyList()
        }
        kafka.committed("failing-$on", input) shouldBe null
    }

    @OptIn(KafkaSpi::class)
    @Test
    fun `records not from Kafka consume are refused, and the outputs the broker already took are aborted`() {
        val output = Topic("unowned-orders")
        // A handle with no group: the transaction's outputs are sent and acknowledged, and only then refused.
        val unowned = object : Handle {
            override fun handled() = Unit
        }
        val records = Stream.of("1", "2").map { value -> Committed(value, Position("elsewhere", 0, 0L), unowned) }

        val exit = producer("unowned").use { orders ->
            records.runTransactionally(orders) { cart -> listOf(output.record(cart, cart)) }.run(Forks()).settled()
        }

        val died = exit.shouldBeInstanceOf<Exit.Died>()
        (listOf(died.cause) + died.cause.suppressed).joinToString { it.message.orEmpty() } shouldContain
            "only records from Kafka.consume"
        withClue("both records reached the broker, in a transaction that was aborted") {
            kafka.readAll(output.name, EVERYTHING).map { it.value() } shouldBe listOf("1", "2")
            kafka.readAll(output.name, COMMITTED) shouldBe emptyList()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a restart after an aborted transaction writes each record exactly once`(on: String) {
        val input = "retried-carts-$on"
        val output = Topic("retried-orders-$on")
        kafka.send(input, "1", "2", "3")
        val attempts = AtomicInteger()
        val onceRefusing = Serializer<String> { _, value ->
            if (value == "3" && attempts.incrementAndGet() == 1) throw SerializationException("not yet")
            value.toByteArray()
        }

        producer("retried-$on", onceRefusing).use { orders ->
            consumed("retried-$on", input)
                .mapRecord { record -> record.value() }
                .take(3)
                .transacted(orders, within = 200.milliseconds) { cart -> listOf(output.record(cart, cart)) }
                .restartOnDefect(Schedule.recurs<Throwable>(1))
                .runFold(0L, Long::plus)
                .run(backend(on))
                .settled() shouldBe Exit.Done(3L)
        }

        kafka.readAll(output.name, COMMITTED).map { it.value() } shouldBe listOf("1", "2", "3")
        kafka.committed("retried-$on", input) shouldBe 3L
    }
}
