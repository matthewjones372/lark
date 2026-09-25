package io.github.matthewjones372.lark.stream

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

/** Each collapsing rule, on a pipeline built to trigger it, and read back through `render(optimised = true)`. */
class CollapseTest {

    private data class Odd(val value: Int)

    private val numbers = Stream.of(1, 2, 3, 4, 5)

    private fun Stream<*, *>.described(): String = render()

    private fun Stream<*, *>.compiled(): String = render(optimised = true)

    @Test
    fun `take after take is one take of the smaller count`() {
        val stream = numbers.take(4).take(2)

        stream.node.collapsed().shouldBeInstanceOf<Node.Take>().let { take ->
            take.n shouldBe 2
            take.upstream shouldBeSameInstanceAs numbers.node
        }
        stream.described() shouldBe "Stream.from\ntake(4)\ntake(2)"
        stream.compiled() shouldBe "Stream.from\ntake(2)"
    }

    @Test
    fun `drop after drop is one drop of both counts, and a sum past Long's range stays at its end`() {
        numbers.drop(1).drop(2).node.collapsed().shouldBeInstanceOf<Node.Drop>().n shouldBe 3
        numbers.drop(Long.MAX_VALUE).drop(2).node.collapsed().shouldBeInstanceOf<Node.Drop>().n shouldBe Long.MAX_VALUE
    }

    @Test
    fun `a recovery over a stream that cannot fail is removed, and one over a stream that can is kept`() {
        val safe = numbers.map { it + 1 }.catchAll { Stream.of(0) }
        val risky = numbers.mapOrFail { if (it == 3) raise(Odd(it)) else it }.catchAll { Stream.of(0) }

        safe.node.collapsed().shouldBeInstanceOf<Node.Map>()
        safe.compiled() shouldNotContain "catchAll"
        risky.node.collapsed().shouldBeInstanceOf<Node.CatchAll>()
        risky.compiled() shouldContain "catchAll"
    }

    @Test
    fun `mapError and orElse over a stream that cannot fail are removed too`() {
        numbers.filter { it > 1 }.mapError { _: Nothing -> Odd(0) }.node.collapsed()
            .shouldBeInstanceOf<Node.Filter>()
        numbers.orElse(Stream.of(9)).node.collapsed() shouldBeSameInstanceAs numbers.node
    }

    @Test
    fun `a recovery in a pipe is kept, because the stream it will be spliced onto is not known`() {
        val pipe = Pipe.identity<Int>().catchAll<Nothing, Odd, Int, Int> { Stream.of(0) }

        pipe.node.collapsed().shouldBeInstanceOf<Node.CatchAll>()
    }

    @Test
    fun `a tree no rule applies to comes back as the same tree`() {
        val stream = numbers.map { it * 2 }.take(3).merge(Stream.of(7).drop(1)).filter { it > 1 }

        stream.node.collapsed() shouldBeSameInstanceAs stream.node
    }

    @Test
    fun `a rendering says where each operator was written, before and after`() {
        val stream = numbers.map { it + 1 }.filter { it > 2 }

        stream.described() shouldContain Regex("""map +CollapseTest.kt:\d+""")
        stream.described() shouldContain Regex("""filter +CollapseTest.kt:\d+""")
        stream.compiled() shouldContain Regex("""fused\[map, filter] +CollapseTest.kt:\d+""")
    }
}
