package io.github.matthewjones372.lark.structured

import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.Deferred
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class StructuredTest {

    private data object Boom

    private data object Slow

    @Test
    fun `a fork nobody awaits never runs`() {
        val runs = AtomicInteger()
        structured<Nothing, Int>("unasked") {
            async { runs.incrementAndGet() }
            1
        } shouldBe 1.right()
        runs.get() shouldBe 0
    }

    @Test
    fun `await runs that fork alone, and only once`() {
        val runs = AtomicInteger()
        val other = AtomicInteger()
        structured<Nothing, Int>("once") {
            val once = async { runs.incrementAndGet() }
            async { other.incrementAndGet() }
            once.await() + once.await()
        } shouldBe 2.right()
        runs.get() shouldBe 1
        other.get() shouldBe 0
    }

    @Test
    fun `awaitAll runs its forks at the same time`() {
        // Each fork waits for the other to arrive: run one after the other, the first would time out.
        val both = CyclicBarrier(2)
        structured<Nothing, Pair<String, String>>("together") {
            val a = async {
                both.await(5, TimeUnit.SECONDS)
                "a"
            }
            val b = async {
                both.await(5, TimeUnit.SECONDS)
                "b"
            }
            awaitAll(a, b)
        } shouldBe ("a" to "b").right()
    }

    @Test
    fun `awaitAll answers in order, for a list and for three`() {
        structured<Nothing, List<Int>>("list") {
            awaitAll(listOf(async { 1 }, async { 2 }, async { 3 }))
        } shouldBe listOf(1, 2, 3).right()
        structured<Nothing, Triple<Int, String, Boolean>>("three") {
            awaitAll(async { 1 }, async { "two" }, async { true })
        } shouldBe Triple(1, "two", true).right()
    }

    @Test
    fun `the first fork to fail is the answer, without waiting for a slow sibling`() {
        val blocker = Blocker()
        structured<Boom, Pair<String, Int>>("fails") {
            val slow = async { blocker.body() }
            val failing = async<Int> {
                blocker.awaitStart()
                raise(Boom)
            }
            awaitAll(slow, failing)
        } shouldBe Boom.left()
        blocker.wasInterrupted() shouldBe true
    }

    @Test
    fun `a fork's raise is the scope's Left, with no ExecutionException to unwrap`() {
        structured<Boom, Int>("raises") {
            async<Int> { raise(Boom) }.await()
        } shouldBe Boom.left()
    }

    @Test
    fun `a fork's throw reaches the awaiting block as itself`() {
        shouldThrow<IllegalArgumentException> {
            structured<Nothing, Int>("throws") { async<Int> { throw IllegalArgumentException("bad") }.await() }
        }
    }

    @Test
    fun `a cancelled fork never runs, and awaiting it answers with the cancel`() {
        val runs = AtomicInteger()
        shouldThrow<InterruptedException> {
            structured<Nothing, Int>("cancelled") {
                val dropped = async { runs.incrementAndGet() }
                dropped.cancel()
                dropped.await()
            }
        }
        runs.get() shouldBe 0
    }

    @Test
    fun `a deadline is a typed error, and the fork it cut short was interrupted`() {
        val blocker = Blocker()
        structured<Slow, String>("deadline", timeout = 100.milliseconds, onTimeout = { Slow }) {
            async { blocker.body() }.await()
        } shouldBe Slow.left()
        blocker.wasInterrupted() shouldBe true
    }

    @Test
    fun `a fork awaited after the deadline answers with it rather than hanging`() {
        structured<Slow, String>("late", timeout = 50.milliseconds, onTimeout = { Slow }) {
            val late = async { "never" }
            spinFor(150)
            late.await()
        } shouldBe Slow.left()
    }

    @Test
    fun `a block that outlives its deadline answers with it`() {
        structured<Slow, String>("overran", timeout = 50.milliseconds, onTimeout = { Slow }) {
            spinFor(150)
            "done anyway"
        } shouldBe Slow.left()
    }

    @Test
    fun `a fork awaited from another fork is refused on the spot`() {
        shouldThrow<WrongThreadException> {
            structured<Nothing, Int>("across") {
                val a = async { 1 }
                async { a.await() + 1 }.await()
            }
        }
    }

    @Test
    fun `awaitAll refuses a fork from another scope`() {
        shouldThrow<IllegalArgumentException> {
            structured<Nothing, List<Int>>("outer") {
                val outer = async { 1 }
                structured("inner") { awaitAll(listOf(outer)) }
            }
        }
    }

    @Test
    fun `a fork that escaped its scope cannot start after the scope closed`() {
        val escaped: Deferred<Int> = structured<Nothing, Deferred<Int>>("escape") { async { 1 } }.getOrNull()!!
        shouldThrow<IllegalStateException> { escaped.await() }
    }

    private fun spinFor(millis: Long) {
        val until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis)
        while (System.nanoTime() < until) Thread.onSpinWait()
    }
}
