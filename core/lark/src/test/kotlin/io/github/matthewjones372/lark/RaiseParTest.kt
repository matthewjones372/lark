package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.github.matthewjones372.lark.assert.shouldBeLeft
import io.github.matthewjones372.lark.assert.shouldBeRight
import io.github.matthewjones372.lark.fail.attempt
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test

class RaiseParTest {

    @Test
    fun `raceN takes a Raise receiver`() {
        val loser = Sleeper()
        either<Bad, Either<String, Int>> {
            raceN(
                { loser.losingWith("lost") },
                {
                    loser.awaitStart()
                    42
                },
            )
        } shouldBe 42.right().right()

        val second = Sleeper()
        val third = Sleeper()
        either<Bad, Either<String, Either<Int, Boolean>>> {
            raceN(
                {
                    second.awaitStart()
                    third.awaitStart()
                    "won"
                },
                { second.losingWith(0) },
                { third.losingWith(false) },
            )
        } shouldBe "won".left().right()
    }

    @Test
    fun `parZip outside any Raise combines its branches' values`() {
        parZip({ 20 }, { 22 }) { a, b -> a + b } shouldBe 42
        parZip({ "a" }, { "b" }, { "c" }) { a, b, c -> a + b + c } shouldBe "abc"
        parZip({ "a" }, { "b" }, { "c" }, { "d" }) { a, b, c, d -> a + b + c + d } shouldBe "abcd"
        parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e -> a + b + c + d + e } shouldBe "abcde"
        parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }) { a, b, c, d, e, f ->
            a + b + c + d + e + f
        } shouldBe "abcdef"
        parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }) { a, b, c, d, e, f, g ->
            a + b + c + d + e + f + g
        } shouldBe "abcdefg"
        parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }) { a, b, c, d, e, f, g, h ->
            a + b + c + d + e + f + g + h
        } shouldBe "abcdefgh"
        parZip(
            { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }, { "i" },
        ) { a, b, c, d, e, f, g, h, i -> a + b + c + d + e + f + g + h + i } shouldBe "abcdefghi"
    }

    @Test
    fun `a throw in a top-level branch rethrows the same instance, and the sibling is interrupted`() {
        val boom = Boom()
        val sleeper = Sleeper()

        val thrown = shouldThrow<Boom> {
            parZip(
                { sleeper.body() },
                {
                    sleeper.awaitStart()
                    throw boom
                },
            ) { _, _ -> 0 }
        }

        thrown shouldBeSameInstanceAs boom
        sleeper.wasInterrupted() shouldBe true
        sleeper.isAlive() shouldBe false
    }

    @Test
    fun `parMap outside any Raise answers in input order`() {
        parMap(listOf(1, 2, 3, 4)) { it * 2 } shouldBe listOf(2, 4, 6, 8)
    }

    @Test
    fun `the loser of a top-level race observes an interrupt`() {
        val loser = Sleeper()

        val raced = raceN(
            { loser.losingWith("lost") },
            {
                loser.awaitStart()
                42
            },
        )

        raced shouldBe 42.right()
        loser.wasInterrupted() shouldBe true
        loser.isAlive() shouldBe false
    }

    @Test
    fun `a top-level three-branch race places its winner on the side it was given`() {
        val second = Sleeper()
        val third = Sleeper()

        val raced = raceN(
            {
                second.awaitStart()
                third.awaitStart()
                "won"
            },
            { second.losingWith(0) },
            { third.losingWith(false) },
        )

        raced shouldBe "won".left()
        second.wasInterrupted() shouldBe true
        third.wasInterrupted() shouldBe true
    }

    @Test
    fun `flock inside attempt forks with async, and a fail surfaces at await as the Left`() {
        val summed = attempt<Bad, Int> {
            flock {
                val first = async { 20 }
                val second = async { 22 }
                first.await() + second.await()
            }
        }

        summed shouldBeRight 42

        val failed = attempt<Bad, Int> {
            flock { async<Int> { fail(Bad("forked")) }.await() }
        }

        failed shouldBeLeft Bad("forked")
    }
}
