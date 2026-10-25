package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.shouldBeSameInstanceAs
import io.github.matthewjones372.lark.junit.shouldContainExactly
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.junit.shouldStartWith
import io.github.matthewjones372.lark.resource

/** The fusing pass, read off the tree it answers with: which operators merge, and which stay apart. */
object FuseSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private val source = Stream.of(1, 2, 3)

    init {
        test("adjacent element-at-a-time operators become one fused node, their steps in order") { _ ->
            val stream = source.map { it + 1 }
                .filter { it > 1 }
                .mapOrFail<Nothing, Int, Int> { it * 2 }
                .filterNot { it > 9 }

            stream.node.fused().shouldBeInstanceOf<Node.Fused> { fused ->
                (
                    fused.steps.map { it.operator } shouldContainExactly
                        listOf("map", "filter", "mapOrFail", "filterNot")
                    ) and
                    (fused.upstream shouldBeSameInstanceAs source.node) and
                    (fused.operator shouldBe "fused[map, filter, mapOrFail, filterNot]") and
                    fused.site.shouldNotBeNull { site -> site shouldStartWith "FuseSuite.kt:" }
            }
        }

        test("an operator that is not one element at a time splits the run, and a run of one is left alone") { _ ->
            val stream = source.map { it + 1 }.map { it * 2 }.take(2).filter { it > 0 }

            stream.node.fused().shouldBeInstanceOf<Node.Filter> { filter ->
                filter.upstream.shouldBeInstanceOf<Node.Take> { take ->
                    take.upstream.shouldBeInstanceOf<Node.Fused> { fused ->
                        (fused.steps.map { it.operator } shouldContainExactly listOf("map", "map")) and
                            (fused.upstream shouldBeSameInstanceAs source.node)
                    }
                }
            }
        }

        test("runs on both sides of a fan-in are fused, and the fan-in is kept") { _ ->
            val left = source.map { it + 1 }.map { it * 2 }
            val right = source.filter { it > 1 }.filterNot { it > 2 }

            left.merge(right).node.fused().shouldBeInstanceOf<Node.Merge> { merged ->
                merged.upstream.shouldBeInstanceOf<Node.Fused> { fused ->
                    fused.steps.map { it.operator } shouldContainExactly listOf("map", "map")
                } and merged.other.shouldBeInstanceOf<Node.Fused> { fused ->
                    fused.steps.map { it.operator } shouldContainExactly listOf("filter", "filterNot")
                }
            }
        }

        test("a tree with nothing to fuse comes back as it went in") { _ ->
            val stream = source.take(1)

            stream.node.fused().shouldBeInstanceOf<Node.Take> { it.upstream shouldBeSameInstanceAs source.node }
        }

        test("a step body answers null where a filter drops, and runs the rest of the steps where it keeps") { _ ->
            source.map { it + 1 }.filter { it % 2 == 0 }.map { it * 10 }
                .node.fused().shouldBeInstanceOf<Node.Fused> { fused ->
                    val steps = fused.steps.map { it.body() }.toTypedArray()

                    (steps.through(1) shouldBe 20) and (steps.through(2) shouldBe null)
                }
        }
    }
}
