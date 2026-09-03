package io.github.matthewjones372.lark

import arrow.core.left
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

class ParTest {

    @Test
    fun `parZip combines the branches' values, each branch on a virtual thread of its own`() {
        val bothRunning = CountDownLatch(2)

        val zipped = flock<Bad, Pair<Int, Thread>> {
            parZip(
                {
                    bothRunning.rendezvous() shouldBe true
                    Thread.currentThread()
                },
                {
                    bothRunning.rendezvous() shouldBe true
                    21
                },
            ) { thread, half -> half * 2 to thread }
        }

        val (doubled, branch) = zipped.getOrNull().shouldNotBeNull()
        doubled shouldBe 42
        withClue("neither branch gets past the rendezvous unless the other is running too") {
            branch.isVirtual shouldBe true
        }
        branch shouldNotBeSameInstanceAs Thread.currentThread()
    }

    @Test
    fun `parZip combines three and four branches`() {
        flock<Bad, String> {
            parZip({ "a" }, { "b" }, { "c" }) { a, b, c -> a + b + c }
        } shouldBe "abc".right()

        flock<Bad, String> {
            parZip({ "a" }, { "b" }, { "c" }, { "d" }) { a, b, c, d -> a + b + c + d }
        } shouldBe "abcd".right()
    }

    @Test
    fun `a raise in one branch is the scope's Left, and the sibling is interrupted`() {
        val sleeper = Sleeper()

        val startedAt = System.nanoTime()
        val result = flock<Bad, Int> {
            parZip(
                { sleeper.body() },
                {
                    sleeper.awaitStart()
                    raise(Bad("branch"))
                },
            ) { _, _ -> 0 }
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        result shouldBe Bad("branch").left()
        sleeper.wasInterrupted() shouldBe true
        withClue("the raise interrupts the sibling, so parZip cannot have waited out its sleep") {
            (elapsedMillis < PROMPT_MILLIS) shouldBe true
        }
        sleeper.isAlive() shouldBe false
    }

    @Test
    fun `a throw in one branch rethrows the same instance, and the sibling is interrupted`() {
        val boom = Boom()
        val sleeper = Sleeper()

        val thrown = shouldThrow<Boom> {
            flock<Bad, Int> {
                parZip(
                    { sleeper.body() },
                    {
                        sleeper.awaitStart()
                        throw boom
                    },
                ) { _, _ -> 0 }
            }
        }

        thrown shouldBeSameInstanceAs boom
        sleeper.wasInterrupted() shouldBe true
        sleeper.isAlive() shouldBe false
    }

    @Test
    fun `when two branches fail, the one started first surfaces`() {
        val firstPastBlocking = CountDownLatch(1)

        val result = flock<Bad, Int> {
            parZip(
                {
                    firstPastBlocking.countDown()
                    raise(Bad("first"))
                },
                {
                    // The branch that raises second waits for the first to be past every blocking call, so
                    // the interrupt this raise sends it cannot land in place of its raise.
                    firstPastBlocking.await()
                    raise(Bad("second"))
                },
            ) { _, _ -> 0 }
        }

        withClue("start order, not finishing order, names the failure") {
            result shouldBe Bad("first").left()
        }
    }

    @Test
    fun `parMap answers in input order, having run the elements in parallel`() {
        val allRunning = CountDownLatch(4)

        val doubled = flock<Bad, List<Int>> {
            parMap(listOf(1, 2, 3, 4)) { element ->
                allRunning.rendezvous() shouldBe true
                element * 2
            }
        }

        doubled shouldBe listOf(2, 4, 6, 8).right()
    }

    @Test
    fun `parMap over an empty iterable is an empty list, and forks nothing`() {
        val ran = AtomicBoolean(false)

        val mapped = flock<Bad, List<Int>> {
            parMap(emptyList<Int>()) {
                ran.set(true)
                0
            }
        }

        mapped shouldBe emptyList<Int>().right()
        ran.get() shouldBe false
    }

    @Test
    fun `a raise in one parMap element interrupts the rest and surfaces`() {
        val sleeper = Sleeper()

        val result = flock<Bad, List<Int>> {
            parMap(listOf(1, 2)) { element ->
                if (element == 1) {
                    sleeper.body()
                    element
                } else {
                    sleeper.awaitStart()
                    raise(Bad("element $element"))
                }
            }
        }

        result shouldBe Bad("element 2").left()
        sleeper.wasInterrupted() shouldBe true
        sleeper.isAlive() shouldBe false
    }

    @Test
    fun `the enclosing scope closes with the branch's Left, not with the interrupt parZip sent`() {
        val started = CountDownLatch(1)

        val result = flock<Bad, Int> {
            parZip(
                {
                    // Nothing here catches the interrupt, so this branch answers with an InterruptedException.
                    started.countDown()
                    Thread.sleep(NEVER_FINISHES_MILLIS)
                    0
                },
                {
                    started.await()
                    raise(Bad("branch"))
                },
            ) { _, _ -> 0 }
        }

        result shouldBe Bad("branch").left()
    }
}
