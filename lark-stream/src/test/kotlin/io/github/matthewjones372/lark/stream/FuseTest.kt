package io.github.matthewjones372.lark.stream

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/** The fusing pass, read off the tree it answers with: which operators merge, and which stay apart. */
class FuseTest {

    private val source = Stream.of(1, 2, 3)

    @Test
    fun `adjacent element-at-a-time operators become one fused node, their steps in order`() {
        val stream = source.map { it + 1 }
            .filter { it > 1 }
            .mapOrFail<Nothing, Int, Int> { it * 2 }
            .filterNot { it > 9 }

        val fused = stream.node.fused().shouldBeInstanceOf<Node.Fused>()

        fused.steps.map { it.operator } shouldContainExactly listOf("map", "filter", "mapOrFail", "filterNot")
        fused.upstream shouldBeSameInstanceAs source.node
        fused.operator shouldBe "fused[map, filter, mapOrFail, filterNot]"
        fused.site!! shouldStartWith "FuseTest.kt:"
    }

    @Test
    fun `an operator that is not one element at a time splits the run, and a run of one is left alone`() {
        val stream = source.map { it + 1 }.map { it * 2 }.take(2).filter { it > 0 }

        val filter = stream.node.fused().shouldBeInstanceOf<Node.Filter>()
        val take = filter.upstream.shouldBeInstanceOf<Node.Take>()
        val fused = take.upstream.shouldBeInstanceOf<Node.Fused>()

        fused.steps.map { it.operator } shouldContainExactly listOf("map", "map")
        fused.upstream shouldBeSameInstanceAs source.node
    }

    @Test
    fun `runs on both sides of a fan-in are fused, and the fan-in is kept`() {
        val left = source.map { it + 1 }.map { it * 2 }
        val right = source.filter { it > 1 }.filterNot { it > 2 }

        val merged = left.merge(right).node.fused().shouldBeInstanceOf<Node.Merge>()

        merged.upstream.shouldBeInstanceOf<Node.Fused>().steps.map { it.operator } shouldContainExactly
            listOf("map", "map")
        merged.other.shouldBeInstanceOf<Node.Fused>().steps.map { it.operator } shouldContainExactly
            listOf("filter", "filterNot")
    }

    @Test
    fun `a tree with nothing to fuse comes back as it went in`() {
        val stream = source.take(1)

        stream.node.fused().shouldBeInstanceOf<Node.Take>().upstream shouldBeSameInstanceAs source.node
    }

    @Test
    fun `a step body answers null where a filter drops, and runs the rest of the steps where it keeps`() {
        val steps = source.map { it + 1 }.filter { it % 2 == 0 }.map { it * 10 }
            .node.fused().shouldBeInstanceOf<Node.Fused>().steps.map { it.body() }.toTypedArray()

        steps.through(1) shouldBe 20
        steps.through(2) shouldBe null
    }
}
