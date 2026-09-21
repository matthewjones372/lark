package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.ensure
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

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

        sum shouldBe 42.right()
    }

    @Test
    fun `a raise inside a fork surfaces at await as the block's Left`() {
        val afterTheAwait = AtomicBoolean(false)

        val result = flock<Bad, Int> {
            val forked = async<Int> { raise(Bad("forked")) }
            val value = forked.await()
            afterTheAwait.set(true)
            value
        }

        result shouldBe Bad("forked").left()
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

        result shouldBe "returned".right()
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

        result shouldBe "returned".right()
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

        result shouldBe "returned".right()
    }

    @Test
    fun `a fork cancelled after it raised does not fail the block`() {
        val result = flock<Bad, String> {
            val fork = async<Int> { raise(Bad("forked")) }
            fork.cancel()
            "returned"
        }

        result shouldBe "returned".right()
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

        result shouldBe 42.right()
        sleeper.wasInterrupted() shouldBe true
    }

    @Test
    fun `a fork that raised and was never awaited becomes the block's Left`() {
        val result = flock<Bad, String> {
            async { raise(Bad("orphan")) }
            "returned anyway"
        }

        result shouldBe Bad("orphan").left()
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
    fun `when two unawaited forks fail, the first in start order is the scope's failure`() {
        val secondStarted = CountDownLatch(1)
        val bothAtTheRaise = CountDownLatch(2)

        val result = flock<Bad, String> {
            async {
                // The fork started first fails last, so finishing order would answer "second".
                secondStarted.await()
                bothAtTheRaise.countDown()
                raise(Bad("first"))
            }
            async<Nothing> {
                secondStarted.countDown()
                bothAtTheRaise.countDown()
                raise(Bad("second"))
            }
            bothAtTheRaise.await()
            "returned anyway"
        }

        withClue("start order, not finishing order, names the scope's failure") {
            result shouldBe Bad("first").left()
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

        outcome shouldBe ("outer done" to false).right()
        inner.wasInterrupted() shouldBe true
    }

    @Test
    fun `a raise on the calling thread interrupts and joins a running fork before flock returns`() {
        val sleeper = Sleeper()

        val result = flock<Bad, String> {
            async { sleeper.body() }
            sleeper.awaitStart()
            raise(Bad("caller"))
        }

        result shouldBe Bad("caller").left()
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

        bound shouldBe 42.right()
        flock<Bad, Int> { broken.bind() } shouldBe Bad("bound").left()
        flock<Bad, Int> {
            ensure(false) { Bad("ensured") }
            1
        } shouldBe Bad("ensured").left()
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

        bound shouldBe 42.right()
        flock<Bad, Int> { async { broken.bind() }.await() } shouldBe Bad("bound").left()
    }
}
