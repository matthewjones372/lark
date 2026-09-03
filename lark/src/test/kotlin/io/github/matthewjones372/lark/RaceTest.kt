package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

class RaceTest {

    @Test
    fun `the branch that returns first wins, on the side it was given, and the loser is interrupted`() {
        val loser = Sleeper()

        val startedAt = System.nanoTime()
        val raced = flock<Bad, Either<String, Int>> {
            raceN(
                { loser.losingWith("lost") },
                {
                    loser.awaitStart()
                    42
                },
            )
        }
        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

        raced shouldBe 42.right().right()
        loser.wasInterrupted() shouldBe true
        withClue("the winner interrupts the loser, so the race cannot have waited out its sleep") {
            (elapsedMillis < PROMPT_MILLIS) shouldBe true
        }
        loser.isAlive() shouldBe false
    }

    @Test
    fun `a branch that fails first loses the race for everyone`() {
        val sleeper = Sleeper()

        val raced = flock<Bad, Either<String, Int>> {
            raceN(
                { sleeper.losingWith("lost") },
                {
                    sleeper.awaitStart()
                    raise(Bad("broken"))
                },
            )
        }

        raced shouldBe Bad("broken").left()
        sleeper.wasInterrupted() shouldBe true
        sleeper.isAlive() shouldBe false
    }

    @Test
    fun `a three-branch race places its winner on the side it was given`() {
        val second = Sleeper()
        val third = Sleeper()
        val firstWon = flock<Bad, Either<String, Either<Int, Boolean>>> {
            raceN(
                {
                    second.awaitStart()
                    third.awaitStart()
                    "won"
                },
                { second.losingWith(0) },
                { third.losingWith(false) },
            )
        }
        firstWon shouldBe "won".left().right()

        val firstOfThree = Sleeper()
        val thirdOfThree = Sleeper()
        val secondWon = flock<Bad, Either<String, Either<Int, Boolean>>> {
            raceN(
                { firstOfThree.losingWith("lost") },
                {
                    firstOfThree.awaitStart()
                    thirdOfThree.awaitStart()
                    42
                },
                { thirdOfThree.losingWith(false) },
            )
        }
        secondWon shouldBe 42.left().right().right()

        val firstLoser = Sleeper()
        val secondLoser = Sleeper()
        val thirdWon = flock<Bad, Either<String, Either<Int, Boolean>>> {
            raceN(
                { firstLoser.losingWith("lost") },
                { secondLoser.losingWith(0) },
                {
                    firstLoser.awaitStart()
                    secondLoser.awaitStart()
                    true
                },
            )
        }
        thirdWon shouldBe true.right().right().right()
    }

    @Test
    fun `a loser that does not catch the interrupt still leaves the scope with the winner's value`() {
        val started = CountDownLatch(1)
        val loser = AtomicReference<Thread?>(null)

        val raced = flock<Bad, Either<Int, String>> {
            raceN(
                {
                    // Nothing here catches the interrupt, so this branch ends with an InterruptedException.
                    loser.set(Thread.currentThread())
                    started.countDown()
                    Thread.sleep(NEVER_FINISHES_MILLIS)
                    0
                },
                {
                    started.await()
                    "won"
                },
            )
        }

        raced shouldBe "won".right().right()
        withClue("a scope that returns with a loser still running is a leak") {
            loser.get().shouldNotBeNull().isAlive shouldBe false
        }
    }
}
