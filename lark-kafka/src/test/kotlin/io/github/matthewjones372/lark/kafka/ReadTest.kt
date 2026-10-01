package io.github.matthewjones372.lark.kafka

import arrow.core.Either
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.run
import io.github.matthewjones372.lark.stream.take
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

class ReadTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()

        private const val GENEROUS_SECONDS = 60L
    }

    private val backend = Forks()

    private fun properties(group: String? = null): Map<String, Any> =
        mapOf<String, Any>(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap) +
            listOfNotNull(group?.let { ConsumerConfig.GROUP_ID_CONFIG to it }) +
            (ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest")

    /** The values [partition] of [topic] gives from [from] until [until], each seen by [each] as it is handled. */
    private fun values(
        topic: String,
        partition: Int,
        from: From,
        until: Until,
        group: String? = null,
        take: Long = Long.MAX_VALUE,
        each: (String?) -> Unit = {},
    ): List<String?> {
        val seen = ConcurrentLinkedQueue<String?>()
        val strings = StringDeserializer()
        val exit = Kafka.read(properties(group), Topic(topic), partition, strings, strings, from, until)
            .mapRecord { record -> record.value().also { seen += it; each(it) } }
            .take(take)
            .runCommitting()
            .run(backend)
            .toCompletableFuture()
            .get(GENEROUS_SECONDS, TimeUnit.SECONDS)
        exit shouldBe Exit.Done(seen.size.toLong())
        return seen.toList()
    }

    @Test
    fun `each partition read to its end at start gives its own records and ends`() {
        kafka.create("replay", partitions = 3)
        kafka.sendTo("replay", 0, "a", "b")
        kafka.sendTo("replay", 1, "c")
        kafka.sendTo("replay", 2, "d", "e", "f")

        (0..2).map { values("replay", it, From.Earliest, Until.EndAtStart) } shouldBe
            listOf(listOf("a", "b"), listOf("c"), listOf("d", "e", "f"))
    }

    @Test
    fun `a record written after the read opened is not read when it ends at start`() {
        kafka.create("late", partitions = 1)
        kafka.sendTo("late", 0, "a", "b")

        val written = values("late", 0, From.Earliest, Until.EndAtStart) {
            if (it == "a") kafka.sendTo("late", 0, "late")
        }

        written shouldBe listOf("a", "b")
        values("late", 0, From.Earliest, Until.EndAtStart) shouldBe listOf("a", "b", "late")
    }

    @Test
    fun `a partition already at its end gives nothing, at once`() {
        kafka.create("empty", partitions = 2)
        kafka.sendTo("empty", 0, "a")

        values("empty", 1, From.Earliest, Until.EndAtStart) shouldBe emptyList()
        values("empty", 0, From.Latest, Until.EndAtStart) shouldBe emptyList()
    }

    @Test
    fun `a read from an offset starts there`() {
        kafka.create("offset", partitions = 1)
        kafka.sendTo("offset", 0, "a", "b", "c")

        values("offset", 0, From.Offset(1), Until.EndAtStart) shouldBe listOf("b", "c")
    }

    @Test
    fun `a read stopped and resumed from what it committed loses nothing`() {
        kafka.create("resume", partitions = 1)
        kafka.sendTo("resume", 0, "1", "2", "3", "4")

        values("resume", 0, From.Committed, Until.Never, group = "resumer", take = 2) shouldBe listOf("1", "2")
        kafka.committed("resumer", "resume") shouldBe 2L
        kafka.sendTo("resume", 0, "5")
        values("resume", 0, From.Committed, Until.EndAtStart, group = "resumer") shouldBe listOf("3", "4", "5")
    }

    @Test
    fun `a read from the committed offset with no group is refused where it is built`() {
        val strings = StringDeserializer()

        shouldThrow<IllegalArgumentException> {
            Kafka.read(properties(), Topic("t"), 0, strings, strings, From.Committed, Until.Never)
        }.message shouldBe
            "Kafka.read from the committed offset needs a group.id in its properties, and t was read with none"
    }

    @Test
    fun `with decoders, a record that cannot be read is a Left that keeps its place`() {
        kafka.create("decoded", partitions = 1)
        kafka.sendTo("decoded", 0, "1", "two", "3")
        val strings = Decoder(StringDeserializer()) { false }
        val ints = Decoder(Deserializer { _, bytes -> String(bytes).toInt() }) { false }
        val seen = ConcurrentLinkedQueue<Either<DecodeError, Int?>>()

        Kafka.read(properties(), Topic("decoded"), 0, strings, ints, From.Earliest, Until.EndAtStart)
            .mapRecord { decoded -> decoded.map { it.value() }.also { seen += it } }
            .runCommitting()
            .run(backend)
            .toCompletableFuture()
            .get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done(3L)

        seen.map { either -> either.fold({ "error at ${it.offset}" }, { "$it" }) } shouldBe
            listOf("1", "error at 1", "3")
    }
}
