package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.Start.Lazy
import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeLeft
import io.github.matthewjones372.surefold.assert.shouldBeRight
import io.github.matthewjones372.surefold.assert.shouldBeSameInstanceAs
import io.github.matthewjones372.surefold.assert.shouldNotBeNull
import io.github.matthewjones372.surefold.assert.shouldNotBeSameInstanceAs
import io.github.matthewjones372.surefold.assert.shouldThrow
import io.github.matthewjones372.surefold.assert.withClue
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.bind
import io.github.matthewjones372.surefold.fail.ensure
import io.github.matthewjones372.surefold.fail.left
import io.github.matthewjones372.surefold.fail.right
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FlockTest {

    private val twentyOne: Either<Bad, Int> = 21.right()
    private val broken: Either<Bad, Int> = Bad("bound").left()

    @Test
    fun `a value forked with async comes back from await, and the block's value is the Right`() {
        val sum = flock<Bad, Int> {
            val first = async { 20 }
            val second = async { 22 }
            first.await() + second.await()
        }

        sum shouldBeRight 42
    }

    @Test
    fun `a raise inside a fork surfaces at await as the block's Left`() {
        val afterTheAwait = AtomicBoolean(false)

        val result = flock<Bad, Int> {
            val forked = async<Int> { fail(Bad("forked")) }
            val value = forked.await()
            afterTheAwait.set(true)
            value
        }

        result shouldBeLeft Bad("forked")
        withClue("await() must leave the block by raising, not return to it") {
            afterTheAwait.get() shouldBe false
        }
    }

    @Test
    fun `a throw inside a fork rethrows at await as the same instance`() {
        val boom = Boom()

        val thrown = shouldThrow<Boom> {
            flock<Bad, Int> { async<Int> { throw boom }.await() }
        }

        thrown shouldBeSameInstanceAs boom
    }

    @Test
    fun `a fork runs on a virtual thread of its own, not on the caller's`() {
        val caller = Thread.currentThread()

        val forked = flock<Bad, Thread> { async { Thread.currentThread() }.await() }
            .getOrNull()
            .shouldNotBeNull()

        withClue("a fork is started with Thread.ofVirtual(), so isVirtual holds in its body") {
            forked.isVirtual shouldBe true
        }
        forked shouldNotBeSameInstanceAs caller
    }

    @Test
    fun `a fork left running when the block returns is interrupted and joined before flock returns`() {
        val sleeper = Sleeper()

        val startedAt = System.nanoTime()
        val result = flock<Bad, String> {
            async { sleeper.body() }
            sleeper.awaitStart()
            "returned"
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        result shouldBeRight "returned"
        sleeper.wasInterrupted() shouldBe true
        withClue("close interrupts, so flock cannot have waited out the fork's sleep") {
            (elapsedMillis < PROMPT_MILLIS) shouldBe true
        }
        withClue("a scope that returns with a thread still running is a leak") {
            sleeper.isAlive() shouldBe false
        }
    }

    @Test
    fun `a cancelled fork is interrupted and dead before cancel returns`() {
        val sleeper = Sleeper()
        val interruptedAtCancel = AtomicBoolean(false)
        val aliveAtCancel = AtomicBoolean(true)

        val result = flock<Bad, String> {
            val fork = async { sleeper.body() }
            sleeper.awaitStart()
            fork.cancel()
            interruptedAtCancel.set(sleeper.wasInterrupted())
            aliveAtCancel.set(sleeper.isAlive())
            "returned"
        }

        result shouldBeRight "returned"
        interruptedAtCancel.get() shouldBe true
        withClue("cancel returns only once the fork it stopped has ended") {
            aliveAtCancel.get() shouldBe false
        }
    }

    @Test
    fun `a fork cancelled and never awaited does not fail the block`() {
        val started = CountDownLatch(1)

        val result = flock<Bad, String> {
            val fork = async {
                started.countDown()
                // Nothing catches it, so the interrupt is what this fork ends with.
                Thread.sleep(NEVER_FINISHES_MILLIS)
                "slept"
            }
            started.await()
            fork.cancel()
            "returned"
        }

        result shouldBeRight "returned"
    }

    @Test
    fun `a fork cancelled after it raised does not fail the block`() {
        val result = flock<Bad, String> {
            val fork = async<Int> { fail(Bad("forked")) }
            fork.cancel()
            "returned"
        }

        result shouldBeRight "returned"
    }

    @Test
    fun `a sibling of a cancelled fork runs to completion`() {
        val sleeper = Sleeper()

        val result = flock<Bad, Int> {
            val stopped = async { sleeper.body() }
            val sibling = async { 42 }
            sleeper.awaitStart()
            stopped.cancel()
            sibling.await()
        }

        result shouldBeRight 42
        sleeper.wasInterrupted() shouldBe true
    }

    @Test
    fun `a lazy fork nobody awaits never runs, and the scope still returns`() {
        val ran = AtomicBoolean(false)

        val result = flock<Bad, String> {
            async(start = Lazy) { ran.set(true) }
            "returned"
        }

        result shouldBeRight "returned"
        withClue("a scope that closes must not wait on a fork that never ran") {
            ran.get() shouldBe false
        }
    }

    @Test
    fun `a lazy fork runs when it is awaited`() {
        val result = flock<Bad, Int> { async(start = Lazy) { 42 }.await() }

        result shouldBeRight 42
    }

    @Test
    fun `a lazy fork nobody awaits cannot fail the block, where the eager form would`() {
        val lazily = flock<Bad, String> {
            async<Int>(start = Lazy) { fail(Bad("never asked for")) }
            "returned"
        }
        val eagerly = flock<Bad, String> {
            async<Int> { fail(Bad("forked")) }
            "returned"
        }

        lazily shouldBeRight "returned"
        eagerly shouldBeLeft Bad("forked")
    }

    @Test
    fun `a lazy fork awaited twice runs once`() {
        val runs = AtomicInteger(0)

        val result = flock<Bad, Int> {
            val counted = async(start = Lazy) { runs.incrementAndGet() }
            counted.await() + counted.await()
        }

        result shouldBeRight 2
        runs.get() shouldBe 1
    }

    @Test
    fun `a lazy fork cancelled before it was awaited never runs`() {
        val ran = AtomicBoolean(false)

        val result = flock<Bad, String> {
            val fork = async(start = Lazy) { ran.set(true) }
            fork.cancel()
            "returned"
        }

        result shouldBeRight "returned"
        ran.get() shouldBe false
    }

    @Test
    fun `a fork that raised and was never awaited becomes the block's Left`() {
        val result = flock<Bad, String> {
            async { fail(Bad("orphan")) }
            "returned anyway"
        }

        result shouldBeLeft Bad("orphan")
    }

    @Test
    fun `a fork that threw and was never awaited rethrows when the scope closes`() {
        val boom = Boom()

        val thrown = shouldThrow<Boom> {
            flock<Bad, String> {
                async { throw boom }
                "returned anyway"
            }
        }

        thrown shouldBeSameInstanceAs boom
    }

    @Test
    fun `scope answers its block's value, with every fork ended by the time it does`() {
        val forkEnded = AtomicBoolean(false)

        val sum: Int = scope {
            async { forkEnded.set(true) }
            async { 20 }.await() + 22
        }

        sum shouldBe 42
        forkEnded.get() shouldBe true
    }

    @Test
    fun `a fork that threw and was never awaited rethrows from scope`() {
        val boom = Boom()

        val thrown = shouldThrow<Boom> {
            scope {
                async { throw boom }
                "returned anyway"
            }
        }

        thrown shouldBeSameInstanceAs boom
    }

    @Test
    fun `when two unawaited forks fail, the first in start order is the scope's failure`() {
        val secondStarted = CountDownLatch(1)
        val bothAtTheRaise = CountDownLatch(2)

        val result = flock<Bad, String> {
            async {
                // The fork started first fails last, so finishing order would answer "second".
                secondStarted.await()
                bothAtTheRaise.countDown()
                fail(Bad("first"))
            }
            async<Nothing> {
                secondStarted.countDown()
                bothAtTheRaise.countDown()
                fail(Bad("second"))
            }
            bothAtTheRaise.await()
            "returned anyway"
        }

        withClue("start order, not finishing order, names the scope's failure") {
            result shouldBeLeft Bad("first")
        }
    }

    @Test
    fun `a fork forked inside a fork is joined when the outer fork's body ends`() {
        val inner = Sleeper()

        val outcome = flock<Bad, Pair<String, Boolean>> {
            val outer = async {
                async { inner.body() }
                inner.awaitStart()
                "outer done"
            }
            outer.await() to inner.isAlive()
        }

        outcome shouldBeRight ("outer done" to false)
        inner.wasInterrupted() shouldBe true
    }

    @Test
    fun `a raise on the calling thread interrupts and joins a running fork before flock returns`() {
        val sleeper = Sleeper()

        val result = flock<Bad, String> {
            async { sleeper.body() }
            sleeper.awaitStart()
            fail(Bad("caller"))
        }

        result shouldBeLeft Bad("caller")
        sleeper.wasInterrupted() shouldBe true
        sleeper.isAlive() shouldBe false
    }

    @Test
    fun `bind and ensure work in the block`() {
        val bound = flock<Bad, Int> {
            val value = twentyOne.bind()
            ensure(value == 21) { Bad("not 21") }
            value * 2
        }

        bound shouldBeRight 42
        flock<Bad, Int> { broken.bind() } shouldBeLeft Bad("bound")
        flock<Bad, Int> {
            ensure(false) { Bad("ensured") }
            1
        } shouldBeLeft Bad("ensured")
    }

    @Test
    fun `bind and ensure work in a fork body`() {
        val bound = flock<Bad, Int> {
            async {
                val value = twentyOne.bind()
                ensure(value == 21) { Bad("not 21") }
                value * 2
            }.await()
        }

        bound shouldBeRight 42
        flock<Bad, Int> { async { broken.bind() }.await() } shouldBeLeft Bad("bound")
    }

    @Test
    fun `close hooks run last first, before any fork is interrupted, and can wait on a fork still running`() {
        val ran = mutableListOf<String>()
        val asks = SynchronousQueue<Int>()
        val answers = SynchronousQueue<Int>()

        val closed = scope {
            async {
                try {
                    while (true) answers.put(asks.take() * 2)
                } catch (stopping: InterruptedException) {
                    ran += "fork interrupted"
                }
            }
            onClose { ran += "first" }
            onClose {
                asks.put(21)
                ran += "second, answered ${answers.take()}"
            }
            "done"
        }

        closed shouldBe "done"
        ran shouldBe listOf("second, answered 42", "first", "fork interrupted")
    }

    @Test
    fun `a close hook registered while the flock closes runs too, after the one that registered it`() {
        val ran = mutableListOf<String>()

        scope {
            onClose { ran += "first" }
            onClose {
                ran += "second"
                onClose { ran += "registered while closing" }
            }
        }

        ran shouldBe listOf("second", "registered while closing", "first")
    }

    @Test
    fun `a close hook that throws an Error still interrupts and joins every fork, and the Error is thrown after`() {
        val ran = mutableListOf<String>()

        shouldThrow<AssertionError> {
            scope {
                async {
                    try {
                        CountDownLatch(1).await()
                    } catch (stopping: InterruptedException) {
                        ran += "fork interrupted"
                    }
                }
                onClose { throw AssertionError("the hook broke") }
                7
            }
        }

        ran shouldBe listOf("fork interrupted")
    }

    @Test
    fun `a close hook that throws is logged, and the hooks after it and the close still run`() {
        val ran = mutableListOf<String>()

        val closed = scope {
            onClose { ran += "earlier" }
            onClose { error("the hook broke") }
            7
        }

        closed shouldBe 7
        ran shouldBe listOf("earlier")
    }

    @Test
    fun `an interrupted close hook ends, the rest run, and the closing thread is interrupted afterwards`() {
        val ran = mutableListOf<String>()

        scope {
            onClose { ran += "earlier" }
            onClose { throw InterruptedException("stop waiting") }
        }

        Thread.interrupted() shouldBe true
        ran shouldBe listOf("earlier")
    }
}
