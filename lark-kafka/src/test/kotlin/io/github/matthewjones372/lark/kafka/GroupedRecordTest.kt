@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.logInfo
import io.github.matthewjones372.lark.stream.Exit
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.TestStreams
import io.github.matthewjones372.lark.stream.from
import io.github.matthewjones372.lark.stream.run
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.hours

/** Spec 0167: a batch marks each record it holds handled, on a test's clock. */
class GroupedRecordTest {

    private val handled = ConcurrentLinkedQueue<Position>()

    private fun record(partition: Int, offset: Long, last: Boolean = true): Committed<String> {
        val position = Position("orders", partition, offset)
        val handle = object : Handle {
            override fun handled() {
                handled += position
            }
        }
        return Committed("$partition-$offset", position, handle.takeIf { last })
    }

    @Test
    fun `a batch marks each of its records handled once it reaches the end`() {
        val read = listOf(record(0, 0), record(1, 0), record(0, 1), record(1, 1), record(0, 2))

        Stream.from(read).groupedRecord(5, 1.hours).runCommitting().run(TestStreams())
            .toCompletableFuture().join() shouldBe Exit.Done(1L)

        handled.toList() shouldContainExactlyInAnyOrder read.map { it.position }
    }

    @Test
    fun `a record still expanding at a batch's end is committed with the batch its last element is in`() {
        val read = listOf(record(0, 0), record(0, 1, last = false), record(0, 1))

        Stream.from(read).groupedRecord(2, 1.hours).runCommitting().run(TestStreams())
            .toCompletableFuture().join() shouldBe Exit.Done(2L)

        withClue("the first batch commits offset 0 only; offset 1 waits for its last element, in the second") {
            handled.toList() shouldBe listOf(Position("orders", 0, 0), Position("orders", 0, 1))
        }
    }

    @Test
    fun `a batch's body logs no record's position, and a record's body still does`() {
        val one = record(0, 1)
        val batch = Committed(listOf(one.value), one.position, Batched(listOf(one)))

        fun annotationsOf(c: Committed<*>) = capturingLogs { logs ->
            c.annotated { logInfo("handled") }
            logs.all().single().annotations
        }

        annotationsOf(batch) shouldNotContainKey "kafka.offset"
        annotationsOf(one) shouldContainKey "kafka.offset"
    }

    @Test
    fun `a batch of batches marks each record handled once, as one batch would`() {
        val read = listOf(record(0, 0), record(1, 0), record(0, 1), record(1, 1))

        Stream.from(read).groupedRecord(2, 1.hours).groupedRecord(2, 1.hours).runCommitting().run(TestStreams())
            .toCompletableFuture().join() shouldBe Exit.Done(1L)

        handled.toList() shouldContainExactlyInAnyOrder read.map { it.position }
    }
}
