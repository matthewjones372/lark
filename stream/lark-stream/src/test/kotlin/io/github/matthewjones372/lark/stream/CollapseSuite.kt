package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.shouldBeSameInstanceAs
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.shouldNotContain
import io.github.matthewjones372.lark.resource

/** Each collapsing rule, on a pipeline built to trigger it, and read back through `render(optimised = true)`. */
object CollapseSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private data class Odd(val value: Int)

    private val numbers = Stream.of(1, 2, 3, 4, 5)

    private fun Stream<*, *>.described(): String = render()

    private fun Stream<*, *>.compiled(): String = render(optimised = true)

    init {
        test("take after take is one take of the smaller count") { _ ->
            val stream = numbers.take(4).take(2)

            stream.node.collapsed().shouldBeInstanceOf<Node.Take> { take ->
                (take.n shouldBe 2) and (take.upstream shouldBeSameInstanceAs numbers.node)
            } and
                (stream.described() shouldBe "Stream.from\ntake(4)\ntake(2)") and
                (stream.compiled() shouldBe "Stream.from\ntake(2)")
        }

        test("drop after drop is one drop of both counts, and a sum past Long's range stays at its end") { _ ->
            numbers.drop(1).drop(2).node.collapsed().shouldBeInstanceOf<Node.Drop> { it.n shouldBe 3 } and
                numbers.drop(Long.MAX_VALUE).drop(2).node.collapsed()
                    .shouldBeInstanceOf<Node.Drop> { it.n shouldBe Long.MAX_VALUE }
        }

        test("a recovery over a stream that cannot fail is removed, and one over a stream that can is kept") { _ ->
            val safe = numbers.map { it + 1 }.catchAll { Stream.of(0) }
            val risky = numbers.mapOrFail { if (it == 3) fail(Odd(it)) else it }.catchAll { Stream.of(0) }

            safe.node.collapsed().shouldBeInstanceOf<Node.Map>() and
                (safe.compiled() shouldNotContain "catchAll") and
                risky.node.collapsed().shouldBeInstanceOf<Node.CatchAll>() and
                (risky.compiled() shouldContain "catchAll")
        }

        test("mapError and orElse over a stream that cannot fail are removed too") { _ ->
            numbers.filter { it > 1 }.mapError { _: Nothing -> Odd(0) }.node.collapsed()
                .shouldBeInstanceOf<Node.Filter>() and
                (numbers.orElse(Stream.of(9)).node.collapsed() shouldBeSameInstanceAs numbers.node)
        }

        test("a recovery in a pipe is kept, because the stream it will be spliced onto is not known") { _ ->
            val pipe = Pipe.identity<Int>().catchAll<Nothing, Odd, Int, Int> { Stream.of(0) }

            pipe.node.collapsed().shouldBeInstanceOf<Node.CatchAll>()
        }

        test("a tree no rule applies to comes back as the same tree") { _ ->
            val stream = numbers.map { it * 2 }.take(3).merge(Stream.of(7).drop(1)).filter { it > 1 }

            stream.node.collapsed() shouldBeSameInstanceAs stream.node
        }

        test("a rendering says where each operator was written, before and after") { _ ->
            val stream = numbers.map { it + 1 }.filter { it > 2 }

            expect(Regex("""map +CollapseSuite.kt:\d+""").containsMatchIn(stream.described())) and
                expect(Regex("""filter +CollapseSuite.kt:\d+""").containsMatchIn(stream.described())) and
                expect(Regex("""fused\[map, filter] +CollapseSuite.kt:\d+""").containsMatchIn(stream.compiled()))
        }
    }
}
