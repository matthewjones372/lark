package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.right
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val ONE_THREAD = "lark-on-one"
private const val TWO_THREADS = "lark-on-two"
private const val ANSWER_SECONDS = 10L

/** One thread, named, so a branch that ran on it can say so rather than be timed. */
private fun <A> onOneThread(block: (ExecutorService) -> A): A = borrowing(1, ONE_THREAD, block)

/** Two, for the race: a loser parked on the only thread there was would never let its winner start. */
private fun <A> onTwoThreads(block: (ExecutorService) -> A): A = borrowing(2, TWO_THREADS, block)

private fun <A> borrowing(threads: Int, name: String, block: (ExecutorService) -> A): A {
    val executor = Executors.newFixedThreadPool(threads) { runnable -> Thread(runnable, name) }
    return try {
        block(executor)
    } finally {
        executor.shutdownNow()
    }
}

class OnExecutorTest {

    /**
     * The branches share one thread and run one after another; a combinator waits on the thread that called
     * it rather than on the executor's, so there is nothing here to deadlock.
     */
    @Test
    fun `parZip runs both branches on the executor it is given`() {
        onOneThread { single ->
            val names = flock<Bad, Pair<String, String>> {
                parZip(
                    on = single,
                    { Thread.currentThread().name },
                    { Thread.currentThread().name },
                ) { first, second -> first to second }
            }

            names shouldBe (ONE_THREAD to ONE_THREAD).right()
        }
    }

    /** `async` with no executor of its own forks where the scope was opened, rather than back on a virtual thread. */
    @Test
    fun `flock forks on the executor it was opened with, and async on the one it is given`() {
        onOneThread { single ->
            val names = flock<Bad, Pair<String, String>>(on = single) {
                val scoped = async { Thread.currentThread().name }
                val named = async(on = single) { Thread.currentThread().name }
                scoped.await() to named.await()
            }

            names shouldBe (ONE_THREAD to ONE_THREAD).right()
        }
    }

    @Test
    fun `a fork cancelled by scope close leaves the executor's next task uninterrupted`() {
        onOneThread { single ->
            val sleeper = Sleeper()

            val returned = flock<Bad, String>(on = single) {
                async { sleeper.body() }
                sleeper.awaitStart()
                "returned"
            }

            returned shouldBe "returned".right()
            sleeper.wasInterrupted() shouldBe true
            val next = CompletableFuture<Boolean>()
            single.execute { next.complete(Thread.currentThread().isInterrupted) }
            withClue("the fork clears the flag as its body leaves, so the borrowed thread carries nothing over") {
                next.get(ANSWER_SECONDS, TimeUnit.SECONDS) shouldBe false
            }
        }
    }

    @Test
    fun `raceN interrupts its loser on the executor's thread, and the executor runs what comes next`() {
        onTwoThreads { pool ->
            val loser = Sleeper()
            val ran = CopyOnWriteArrayList<String>()

            val raced = flock<Bad, Either<String, String>> {
                raceN(
                    on = pool,
                    {
                        ran += Thread.currentThread().name
                        loser.losingWith("lost")
                    },
                    {
                        loser.awaitStart()
                        ran += Thread.currentThread().name
                        "won"
                    },
                )
            }

            raced shouldBe "won".right().right()
            loser.wasInterrupted() shouldBe true
            ran shouldContainExactly listOf(TWO_THREADS, TWO_THREADS)
            val next = CompletableFuture<Boolean>()
            pool.execute { next.complete(Thread.currentThread().isInterrupted) }
            next.get(ANSWER_SECONDS, TimeUnit.SECONDS) shouldBe false
        }
    }

    @Test
    fun `a call that names no executor still forks a virtual thread per branch`() {
        val zipped = flock<Bad, Pair<Boolean, Boolean>> {
            parZip({ Thread.currentThread().isVirtual }, { Thread.currentThread().isVirtual }) { a, b -> a to b }
        }

        zipped shouldBe (true to true).right()
        parMap(listOf(1, 2)) { Thread.currentThread().isVirtual } shouldContainExactly listOf(true, true)
        flock<Bad, Boolean> { async { Thread.currentThread().isVirtual }.await() } shouldBe true.right()
    }
}
