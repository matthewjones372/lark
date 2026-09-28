package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

private data class User(val name: String)

private data class Order(val id: Long)

private data class Dashboard(val user: User, val orders: List<Order>)

private object Users {
    fun find(id: Long): Either<Bad, User> = if (id == 1L) User("Ada").right() else Bad("no user $id").left()
}

private object Orders {
    fun forUser(id: Long): Either<Bad, List<Order>> = listOf(Order(id)).right()
}

class RaiseParTest {

    private val users = Users
    private val orders = Orders

    // Spec 0003's example: an `either` written for arrow-fx-coroutines, with the `suspend` dropped.
    private fun dashboard(id: Long): Either<Bad, Dashboard> = either {
        parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
    }

    @Test
    fun `parZip answers inside a plain either, with no scope of lark's around it`() {
        dashboard(1L) shouldBe Dashboard(User("Ada"), listOf(Order(1L))).right()
    }

    @Test
    fun `a raise in a branch is the either's Left`() {
        dashboard(2L) shouldBe Bad("no user 2").left()
    }

    @Test
    fun `each branch raises into a Raise of its own, not into the enclosing one`() {
        val enclosing = AtomicReference<Raise<Bad>?>(null)
        val branch = AtomicReference<Raise<Bad>?>(null)

        val zipped = either<Bad, Int> {
            enclosing.set(this)
            parZip(
                {
                    branch.set(this)
                    20
                },
                { 22 },
            ) { a, b -> a + b }
        }

        zipped shouldBe 42.right()
        withClue("a Raise never crosses a thread, so a fork gets a boundary of its own") {
            branch.get().shouldNotBeNull() shouldNotBeSameInstanceAs enclosing.get().shouldNotBeNull()
        }
    }

    @Test
    fun `parZip runs nine branches`() {
        either<Bad, String> {
            parZip(
                { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }, { "i" },
            ) { a, b, c, d, e, f, g, h, i -> a + b + c + d + e + f + g + h + i }
        } shouldBe "abcdefghi".right()
    }

    @Test
    fun `parZip runs five, six, seven and eight branches`() {
        either<Bad, String> {
            parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e -> a + b + c + d + e }
        } shouldBe "abcde".right()

        either<Bad, String> {
            parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }) { a, b, c, d, e, f ->
                a + b + c + d + e + f
            }
        } shouldBe "abcdef".right()

        either<Bad, String> {
            parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }) { a, b, c, d, e, f, g ->
                a + b + c + d + e + f + g
            }
        } shouldBe "abcdefg".right()

        either<Bad, String> {
            parZip({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }) { a, b, c, d, e, f, g, h ->
                a + b + c + d + e + f + g + h
            }
        } shouldBe "abcdefgh".right()
    }

    @Test
    fun `parMap and raceN take a Raise receiver too`() {
        either<Bad, List<Int>> { parMap(listOf(1, 2, 3)) { it * 2 } } shouldBe listOf(2, 4, 6).right()
        either<Bad, List<Int>> { parMap(listOf(1)) { raise(Bad("mapped")) } } shouldBe Bad("mapped").left()

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
    fun `flock inside either forks with async, and a raise surfaces at await as the Left`() {
        val summed = either<Bad, Int> {
            flock {
                val first = async { 20 }
                val second = async { 22 }
                first.await() + second.await()
            }
        }

        summed shouldBe 42.right()

        val raised = either<Bad, Int> {
            flock { async<Int> { raise(Bad("forked")) }.await() }
        }

        raised shouldBe Bad("forked").left()
    }

    @Test
    fun `a raise is what surfaces, not what a sibling threw once the raise cut it short`() {
        val started = either<Bad, List<Int>> {
            parMap(listOf(0, 1)) { n ->
                if (n == 1) {
                    Thread.sleep(50)
                    raise(Bad("refused"))
                }
                // A pool that turns the interrupt into an exception of its own, as a JDBC pool starting up does.
                try {
                    Thread.sleep(60_000)
                    n
                } catch (interrupted: InterruptedException) {
                    throw IllegalStateException("pool closed by interrupt", interrupted)
                }
            }
        }

        started shouldBe Bad("refused").left()
    }
}
