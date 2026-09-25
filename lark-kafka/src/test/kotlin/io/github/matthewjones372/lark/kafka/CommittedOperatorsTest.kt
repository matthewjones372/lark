package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.run
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
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** The element operators work on the record's value and carry its offset through to `runCommitting`. */
class CommittedOperatorsTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-kafka-committed-operators-test")

        private const val GENEROUS_SECONDS = 60L
    }

    private data class Refused(val value: String)

    private fun subscribe(group: String, topic: String) =
        Kafka.subscribe(
            ConsumerSettings.create(pekko.system, StringDeserializer(), StringDeserializer())
                .withBootstrapServers(kafka.bootstrap)
                .withGroupId(group)
                .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
                .withStopTimeout(Duration.ofSeconds(1)),
            Topic(topic),
        )

    private val committer: CommitterSettings get() = CommitterSettings.create(pekko.system)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    @Test
    fun `mapRecord, filterRecord, mapConcatRecord and mapParRecord see the values, and every record is committed`() {
        kafka.send("numbers", "1", "2", "3", "4", "5")
        val seen = ConcurrentLinkedQueue<Int>()

        subscribe("counting", "numbers")
            .mapRecord { record -> record.value().toInt() }
            .filterRecord { it % 2 == 1 }
            .mapConcatRecord { listOf(it, it * 10) }
            .mapParRecord(2) { n -> n.also(seen::add) }
            .take(6)
            .runCommitting(committer)
            .run(pekko.system)
            .settled() shouldBe Exit.Done(Done.getInstance())

        seen.toList() shouldBe listOf(1, 10, 3, 30, 5, 50)
        kafka.committed("counting", "numbers") shouldBe 5L
    }

    @Test
    fun `a body that raises on record 3 of 5 leaves the first two committed`() {
        kafka.send("refusals", "1", "2", "3", "4", "5")

        subscribe("refusing", "refusals")
            .mapRecordOrFail { record ->
                if (record.value() == "3") fail(Refused("3")) else record.value()
            }
            .runCommitting(committer)
            .run(pekko.system)
            .settled() shouldBe Exit.Failed(Refused("3"))

        withClue("the next run in the group starts at the record that raised") {
            kafka.committed("refusing", "refusals") shouldBe 2L
        }
    }

    @Test
    fun `a record expanded to three elements whose third raises is not committed`() {
        kafka.send("expansions", "x", "y")

        subscribe("expanding", "expansions")
            .mapConcatRecord { record ->
                if (record.value() == "x") listOf(0) else listOf(1, 2, 3)
            }
            .mapParRecordOrFail(1) { n -> if (n == 3) raise(Refused("y")) else n }
            .runCommitting(committer)
            .run(pekko.system)
            .settled() shouldBe Exit.Failed(Refused("y"))

        withClue("x is committed; y's first two elements got through, and still y is not") {
            kafka.committed("expanding", "expansions") shouldBe 1L
        }
    }

    @Test
    fun `a body's log lines name the record it is handling`() {
        kafka.send("annotated", "only")

        val annotations = ConcurrentLinkedQueue<Map<String, String>>()
        subscribe("annotating", "annotated")
            .mapRecord { _ ->
                capturingLogs { logs ->
                    logInfo("handled")
                    logs.all().single().annotations.also(annotations::add)
                }
            }
            .take(1)
            .runCommitting(committer)
            .run(pekko.system)
            .settled() shouldBe Exit.Done(Done.getInstance())

        annotations.toList() shouldBe listOf(
            mapOf("kafka.topic" to "annotated", "kafka.partition" to "0", "kafka.offset" to "0"),
        )
    }
}
