package io.github.matthewjones372.lark.structured

import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class CombinatorsTest {

    private data object Boom

    private data object Slow

    @Test
    fun `parZip combines both branches`() {
        either<Nothing, Int> { parZip({ 20 }, { 22 }) { a, b -> a + b } } shouldBe 42.right()
    }

    @Test
    fun `parZip runs its branches at the same time`() {
        // Each branch waits for the other to arrive: run one after the other, the first would time out.
        val both = CyclicBarrier(2)
        either<Nothing, String> {
            parZip({
                both.await(5, TimeUnit.SECONDS)
                "a"
            }, {
                both.await(5, TimeUnit.SECONDS)
                "b"
            }) { a, b -> a + b }
        } shouldBe "ab".right()
    }

    @Test
    fun `parZip of three keeps each value in its place`() {
        either<Nothing, String> { parZip({ 1 }, { "two" }, { true }) { a, b, c -> "$a $b $c" } } shouldBe
            "1 two true".right()
    }

    @Test
    fun `the first branch to fail cancels the other and is the answer`() {
        val blocker = Blocker()
        either<Boom, String> {
            parZip({ blocker.body() }, {
                blocker.awaitStart()
                raise(Boom)
            }) { a, _ -> a }
        } shouldBe Boom.left()
        blocker.wasInterrupted() shouldBe true
    }

    @Test
    fun `a branch's throw reaches the caller as itself, not an ExecutionException`() {
        shouldThrow<IllegalArgumentException> {
            either<Nothing, Int> { parZip({ throw IllegalArgumentException("bad") }, { 1 }) { _, b -> b } }
        }
    }

    @Test
    fun `parMap answers in the iterable's order`() {
        either<Nothing, List<Int>> { parMap(listOf(1, 2, 3)) { it * 10 } } shouldBe listOf(10, 20, 30).right()
    }

    @Test
    fun `parMap answers with the first element to fail`() {
        either<Int, List<Int>> { parMap(listOf(1, 2, 3)) { if (it == 2) raise(it) else it } } shouldBe 2.left()
    }

    @Test
    fun `raceN answers with the first to finish, on its side, and cancels the loser`() {
        val blocker = Blocker()
        either<Nothing, arrow.core.Either<String, String>> {
            raceN({ blocker.body() }, {
                blocker.awaitStart()
                "fast"
            })
        } shouldBe "fast".right().right()
        blocker.wasInterrupted() shouldBe true
    }

    @Test
    fun `a branch that raises first loses the race for everyone`() {
        val blocker = Blocker()
        either<Boom, arrow.core.Either<String, String>> {
            raceN({ blocker.body() }, {
                blocker.awaitStart()
                raise(Boom)
            })
        } shouldBe Boom.left()
    }

    @Test
    fun `timeout answers in time with the block's value`() {
        either<Slow, Int> { timeout(5.seconds, onTimeout = { Slow }) { 42 } } shouldBe 42.right()
    }

    @Test
    fun `past the deadline, timeout raises the typed error and the block was interrupted`() {
        val blocker = Blocker()
        either<Slow, String> { timeout(100.milliseconds, onTimeout = { Slow }) { blocker.body() } } shouldBe Slow.left()
        blocker.wasInterrupted() shouldBe true
    }

    @Test
    fun `without an error to raise, timeout throws as lark's does`() {
        shouldThrow<TimeoutException> {
            either<Nothing, String> { timeout(100.milliseconds) { Blocker().body() } }
        }
    }

    @Test
    fun `timeoutOrNull answers null past the deadline`() {
        either<Nothing, String?> { timeoutOrNull(100.milliseconds) { Blocker().body() } } shouldBe null.right()
    }

    @Test
    fun `a branch's raise inside a timeout is still the answer`() {
        either<Boom, Int> {
            timeout(5.seconds, onTimeout = { Boom }) { parZip({ raise(Boom) }, { 1 }) { _, b -> b } }
        } shouldBe Boom.left()
    }
}
