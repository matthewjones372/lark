package io.github.matthewjones372.lark.structured

import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.Deferred
import io.github.matthewjones372.lark.Start
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

class StructuredTest {

    private data object Boom

    private data object Slow

    @Test
    fun `awaited forks answer with their values`() {
        structured<Nothing, Int>("sum") {
            val a = async { 20 }
            val b = async { 22 }
            a.await() + b.await()
        } shouldBe 42.right()
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
    fun `a fork nobody awaited still fails its scope`() {
        structured<Boom, Int>("unawaited") {
            val failing = async<Int> { raise(Boom) }
            // Its own answer, and not one the close cut short.
            while (!(failing as Fork<*, *>).hasEnded()) Thread.onSpinWait()
            1
        } shouldBe Boom.left()
    }

    @Test
    fun `a fork the close cuts short does not fail its scope`() {
        val blocker = Blocker()
        structured<Nothing, String>("cut") {
            async { blocker.body() }
            blocker.awaitStart()
            "returned"
        } shouldBe "returned".right()
        blocker.wasInterrupted() shouldBe true
    }

    @Test
    fun `a lazy fork nobody awaits never runs`() {
        val runs = AtomicInteger()
        structured<Nothing, Int>("lazy") {
            async(start = Start.Lazy) { runs.incrementAndGet() }
            1
        } shouldBe 1.right()
        runs.get() shouldBe 0
    }

    @Test
    fun `a lazy fork runs when awaited, and only once`() {
        val runs = AtomicInteger()
        structured<Nothing, Int>("lazy") {
            val once = async(start = Start.Lazy) { runs.incrementAndGet() }
            once.await() + once.await()
        } shouldBe 2.right()
        runs.get() shouldBe 1
    }

    @Test
    fun `cancel interrupts one fork and leaves its sibling running`() {
        val blocker = Blocker()
        structured<Nothing, String>("cancel") {
            val stuck = async { blocker.body() }
            val sibling = async { "sibling" }
            blocker.awaitStart()
            stuck.cancel()
            sibling.await()
        } shouldBe "sibling".right()
        blocker.wasInterrupted() shouldBe true
    }

    @Test
    fun `a lazy fork cancelled before it started never runs`() {
        val runs = AtomicInteger()
        structured<Nothing, Int>("cancelled") {
            async(start = Start.Lazy) { runs.incrementAndGet() }.cancel()
            1
        } shouldBe 1.right()
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
    fun `a lazy fork awaited after the deadline answers with it rather than hanging`() {
        val blocker = Blocker()
        structured<Slow, String>("late", timeout = 50.milliseconds, onTimeout = { Slow }) {
            val lazy = async(start = Start.Lazy) { "never" }
            async { blocker.body() }.also { blocker.awaitStart() }
            while (!blocker.wasInterrupted()) Thread.onSpinWait()
            lazy.await()
        } shouldBe Slow.left()
    }

    @Test
    fun `a block that outlives its deadline answers with it`() {
        val blocker = Blocker()
        structured<Slow, String>("overran", timeout = 50.milliseconds, onTimeout = { Slow }) {
            async { blocker.body() }
            while (!blocker.wasInterrupted()) Thread.onSpinWait()
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
    fun `a lazy fork that escaped its scope cannot start after the scope closed`() {
        val escaped: Deferred<Int> = structured<Nothing, Deferred<Int>>("escape") {
            async(start = Start.Lazy) { 1 }
        }.getOrNull()!!
        shouldThrow<IllegalStateException> { escaped.await() }
    }

    @Test
    fun `a fork onto an executor is refused, since the scope owns its threads`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            shouldThrow<IllegalArgumentException> {
                structured<Nothing, Int>("pool") { async(on = pool) { 1 }.await() }
            }
        } finally {
            pool.shutdown()
        }
    }
}
