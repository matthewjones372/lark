package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeGreaterThan
import io.github.matthewjones372.lark.assert.shouldBeGreaterThanOrEqual
import io.github.matthewjones372.lark.assert.shouldBeInstanceOf
import io.github.matthewjones372.lark.assert.shouldBeLessThan
import io.github.matthewjones372.lark.assert.shouldContainAll
import io.github.matthewjones372.lark.assert.shouldNotBe
import io.github.matthewjones372.lark.assert.shouldNotBeEmpty
import io.github.matthewjones372.lark.assert.withClue
import io.github.matthewjones372.lark.capturingMetrics
import io.github.matthewjones372.lark.fail.fold
import io.github.matthewjones372.lark.metricTagged
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.PekkoStreams
import io.github.matthewjones372.lark.stream.StreamBackend
import io.github.matthewjones372.lark.stream.restartOnDefect
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.start
import io.github.matthewjones372.lark.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

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
        private const val SLOW_RECORD_MILLIS = 500L
        private const val SHORT_POLL_INTERVAL_MILLIS = 1500
        private const val SLOW_HANDLER_MILLIS = 4000L
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
    fun `a batch over two partitions commits each one's last offset in it`(on: String) {
        val topic = "batched-$on"
        kafka.create(topic, partitions = 2)
        kafka.sendTo(topic, 0, "a", "b", "c")
        kafka.sendTo(topic, 1, "x", "y")

        val sizes = ConcurrentLinkedQueue<Int>()
        strings("inserting-$on", topic)
            .groupedRecord(5, 10.seconds)
            .mapRecord { records -> sizes.add(records.size) }
            .take(1)
            .runCommitting()
            .run(backend(on))
            .settled() shouldBe Exit.Done(1L)

        sizes.toList() shouldBe listOf(5)
        kafka.committed("inserting-$on", topic, number = 0) shouldBe 3L
        kafka.committed("inserting-$on", topic, number = 1) shouldBe 2L
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `the next run in the group starts after the last batch committed`(on: String) {
        val topic = "batches-$on"
        kafka.send(topic, "1", "2", "3", "4", "5")

        strings("bulk-$on", topic).groupedRecord(3, 10.seconds).take(1).runCommitting().run(backend(on))
            .settled() shouldBe Exit.Done(1L)

        val seen = ConcurrentLinkedQueue<String>()
        strings("bulk-$on", topic)
            .groupedRecord(2, 10.seconds)
            .mapRecord { records -> records.forEach { seen.add(it.value()) } }
            .take(1)
            .runCommitting()
            .run(backend(on))
            .settled() shouldBe Exit.Done(1L)
        seen.toList() shouldBe listOf("4", "5")
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `mapParRecord passes on what it handled while the topic is quiet, and all of it is committed`(on: String) {
        val topic = "in-parallel-$on"
        kafka.send(topic, "1", "2", "3")

        strings("handling-$on", topic)
            .mapParRecord(4) { record -> record.value().toInt() * 10 }
            .take(3)
            .runCommitting()
            .run(backend(on))
            .settled() shouldBe Exit.Done(3L)

        kafka.committed("handling-$on", topic) shouldBe 3L
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

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `divertLefts hands a bad record on before anything after it is committed`(on: String) {
        val topic = "diverted-$on"
        kafka.send(topic, "fine", "bad", "fine again")
        val strict = Deserializer { _, data: ByteArray ->
            String(data).also { if (it == "bad") throw SerializationException("unreadable") }
        }
        val dead = ConcurrentLinkedQueue<Long>()

        Kafka.consume(
            properties("diverting-$on"),
            Topic(topic),
            key = Decoder.string(),
            value = Decoder(strict) { false },
        )
            .divertLefts { error -> dead.add(error.offset) }
            .take(2)
            .runCommitting()
            .run(backend(on))
            .settled() shouldBe Exit.Done(2L)

        dead.toList() shouldBe listOf(1L)
        kafka.committed("diverting-$on", topic) shouldBe 3L
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a registry that is down restarts the consumer, which reads the same record again`(on: String) {
        val topic = "flaky-$on"
        kafka.send(topic, "order")
        val asked = AtomicInteger()
        val registryOnce = Deserializer { _, data: ByteArray ->
            if (asked.incrementAndGet() == 1) throw SerializationException("registry", IOException("refused"))
            String(data)
        }
        val down: (Throwable) -> Boolean = { t -> generateSequence(t) { it.cause }.any { it is IOException } }

        Kafka.consume(
            properties("restarting-$on"),
            Topic(topic),
            key = Decoder.string(),
            value = Decoder(registryOnce, down),
        )
            .restartOnDefect(Schedule.recurs<Throwable>(1))
            .mapRecord { decoded -> decoded.fold({ "left" }, { it.value() }) }
            .take(1)
            .runCommitting()
            .run(backend(on))
            .settled() shouldBe Exit.Done(1L)

        asked.get() shouldBe 2
        kafka.committed("restarting-$on", topic) shouldBe 1L
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `mapParPartitioned lets a partition pass another's slow record, and keeps each one's order`(on: String) {
        val topic = "partitioned-$on"
        kafka.create(topic, partitions = 2)
        kafka.sendTo(topic, 0, "slow", "after slow")
        kafka.sendTo(topic, 1, "a", "b")
        val arrived = ConcurrentLinkedQueue<String>()
        val never = CountDownLatch(1)

        strings("partitioned-$on", topic)
            .mapParPartitioned(4) { record ->
                if (record.value() == "slow") never.await(SLOW_RECORD_MILLIS, TimeUnit.MILLISECONDS)
                record.value()
            }
            .mapRecord { value -> value.also(arrived::add) }
            .take(4)
            .runCommitting()
            .run(backend(on))
            .settled() shouldBe Exit.Done(4L)

        val order = arrived.toList()
        withClue("partition 1 was not held behind partition 0's slow record: $order") {
            order.indexOf("b") shouldBeLessThan order.indexOf("slow")
        }
        order.filter { it in setOf("slow", "after slow") } shouldBe listOf("slow", "after slow")
        order.filter { it in setOf("a", "b") } shouldBe listOf("a", "b")
        withClue("partition 0's two records") { kafka.committed("partitioned-$on", topic) shouldBe 2L }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a handler slower than the poll interval keeps its group, and each record is handled once`(on: String) {
        val topic = "slow-handler-$on"
        kafka.send(topic, "1", "2", "3")
        // A group that drops a member which has not polled for 1.5s; the first record takes 4s to handle.
        val slow = properties("slow-$on") + (ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to SHORT_POLL_INTERVAL_MILLIS)
        val seen = ConcurrentLinkedQueue<String>()
        val never = CountDownLatch(1)

        // What the meters read as the slow handler started, and as it finished.
        val pollsBefore = AtomicInteger()
        val pollsAfter = AtomicInteger()
        val pausedDuring = AtomicReference<Double?>()
        val (exit, captured) = capturingMetrics { captured ->
            Kafka.consume(slow, Topic(topic), key = StringDeserializer(), value = StringDeserializer())
                .mapRecord { record ->
                    if (record.value() == "1") {
                        pollsBefore.set(captured.histogram("lark.kafka.consumer.poll").size)
                        never.await(SLOW_HANDLER_MILLIS, TimeUnit.MILLISECONDS)
                        pollsAfter.set(captured.histogram("lark.kafka.consumer.poll").size)
                        pausedDuring.set(captured.gauge("lark.kafka.consumer.paused"))
                    }
                    record.value().also(seen::add)
                }
                .take(3)
                .runCommitting()
                .run(backend(on))
                .settled() to captured
        }

        exit shouldBe Exit.Done(3L)
        // On Pekko the source reads ahead on a thread of its own, which keeps polling: the keeper has nothing to do.
        if (on == "Forks") {
            withClue("the keeper paused the partitions it polled for, and its polls were timed") {
                pausedDuring.get() shouldBe 1.0
                pollsAfter.get() shouldBeGreaterThan pollsBefore.get()
            }
            withClue("nothing is paused once the consumer has closed") {
                captured.gauge("lark.kafka.consumer.paused") shouldBe 0.0
            }
        }
        withClue("no record was handed to this consumer twice, as it would be once its group had moved on") {
            seen.toList() shouldBe listOf("1", "2", "3")
        }
        kafka.committed("slow-$on", topic) shouldBe 3L
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `a run records what it read, how long it polled, committed and closed, and what it owned`(on: String) {
        val topic = "metered-$on"
        kafka.send(topic, "one", "two", "three")

        val inFlight = ConcurrentLinkedQueue<Double>()
        val (exit, captured) = capturingMetrics { captured ->
            metricTagged("service" to "orders") {
                strings("metering-$on", topic)
                    .mapRecord { record ->
                        captured.gauge("lark.kafka.consumer.in.flight")?.let(inFlight::add)
                        record.value()
                    }
                    .take(3)
                    .runCommitting()
                    .run(backend(on))
                    .settled()
            } to captured
        }
        withClue("each record was in flight while it was handled: $inFlight") {
            inFlight.size shouldBe 3
            inFlight.forEach { count -> count shouldBeGreaterThanOrEqual 1.0 }
        }

        exit shouldBe Exit.Done(3L)
        captured.counter("lark.kafka.consumer.records") shouldBe 3.0
        withClue("each value's bytes, and no key") { captured.counter("lark.kafka.consumer.bytes") shouldBe 11.0 }
        captured.histogram("lark.kafka.consumer.poll").shouldNotBeEmpty()
        withClue("the close commits what was handled, and waits for it") {
            captured.histogram("lark.kafka.consumer.commit").shouldNotBeEmpty()
        }
        captured.histogram("lark.kafka.consumer.close").size shouldBe 1
        captured.counter("lark.kafka.consumer.rebalances") shouldNotBe 0.0
        withClue("nothing is owned or in flight once the consumer has left") {
            captured.gauge("lark.kafka.consumer.assigned") shouldBe 0.0
            captured.gauge("lark.kafka.consumer.in.flight") shouldBe 0.0
        }
        captured.tags("lark.kafka.consumer.records") shouldContainAll
            mapOf("service" to "orders", "group" to "metering-$on", "topic" to topic, "partition" to "0")
        listOf("records", "bytes", "poll", "commit", "close", "rebalances", "assigned", "in.flight").forEach { meter ->
            withClue("$meter reads back") { captured.read("lark.kafka.consumer.$meter").shouldNotBeEmpty() }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `two consumers in one group with no client id each keep their own meters`(on: String) {
        val topic = "shared-$on"
        kafka.send(topic, "1", "2")
        fun clientOfOneRun() = capturingMetrics { captured ->
            strings("sharing-$on", topic).take(1).runCommitting().run(backend(on)).settled() shouldBe Exit.Done(1L)
            captured.tags("lark.kafka.consumer.assigned")["client"]
        }

        val first = clientOfOneRun()
        val second = clientOfOneRun()

        withClue("each consumer is named, so its gauges are its own: $first, $second") {
            first.isNullOrEmpty() shouldBe false
            first shouldNotBe second
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["Forks", "Pekko"])
    fun `lag reads what is left unread, then nothing, and latency how stale each handled record was`(on: String) {
        val topic = "lagging-$on"
        kafka.send(topic, "1", "2", "3", "4", "5")
        // One record a poll, so a poll leaves the rest fetched and unread.
        val oneAtATime = properties("lagging-$on") + (ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1)
        val lags = ConcurrentLinkedQueue<Double?>()

        val captured = capturingMetrics { captured ->
            Kafka.consume(oneAtATime, Topic(topic), key = StringDeserializer(), value = StringDeserializer())
                .mapRecord { lags.add(captured.gauge("lark.kafka.consumer.lag")) }
                .take(5)
                .runCommitting()
                .run(backend(on))
                .settled() shouldBe Exit.Done(5L)
            captured
        }

        // Pekko's source reads ahead of the handler, so only the last record's reading is the same on both.
        if (on == "Forks") lags.first() shouldBe 4.0
        lags.last() shouldBe 0.0
        captured.tags("lark.kafka.consumer.lag") shouldContainAll mapOf("topic" to topic, "partition" to "0")
        withClue("a partition this consumer has left lags nothing here") {
            captured.gauge("lark.kafka.consumer.lag") shouldBe 0.0
        }
        val latencies = captured.histogram("lark.kafka.consumer.latency")
        latencies.size shouldBe 5
        latencies.forEach { seconds -> seconds shouldBeLessThan GENEROUS_SECONDS.toDouble() }
    }
}
