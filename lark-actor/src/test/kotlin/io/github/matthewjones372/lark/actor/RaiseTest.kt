package io.github.matthewjones372.lark.actor

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

private data object OutOfStock

private sealed interface Till

private data class Sell(val count: Int) : Till

private data class Settle(val owed: Either<OutOfStock, Int>) : Till

private data class Try(val count: Int) : Till

private data class Count(val reply: Reply<Int>) : Till

private data object Explode : Till

/** Stock that raises [OutOfStock] rather than going below zero, and answers a failed `Try` without failing. */
private fun till(stock: Int) = behaviour<Till, Int, OutOfStock>(stock) { _, left, message ->
    when (message) {
        is Sell -> if (message.count > left) raise(OutOfStock) else become(left - message.count)

        is Settle -> become(left - message.owed.bind())

        is Try -> {
            val attempt = either { if (message.count > left) raise(OutOfStock) else left - message.count }
            become(attempt.getOrNull() ?: left)
        }

        is Count -> {
            message.reply(left)
            stay()
        }

        Explode -> error("the till caught fire")
    }
}

class RaiseTest {

    @Test
    fun `a raise stops the actor, and is its failure`() {
        val till = till(3).test()

        till.send(Sell(5))

        till.stopped shouldBe true
        till.failure shouldBe Failure.Raised(OutOfStock)
    }

    @Test
    fun `a bind on a Left is a raise`() {
        val till = till(3).test()

        till.send(Settle(OutOfStock.left()))

        till.failure shouldBe Failure.Raised(OutOfStock)
    }

    @Test
    fun `a raise inside the step's own either is that either's, and the actor carries on`() {
        val till = till(3).test()

        till.send(Try(5))
        till.send(Settle(2.right()))

        till.stopped shouldBe false
        till.state shouldBe 1
    }

    @Test
    fun `a throw is a Thrown failure, and still reaches the test`() {
        val till = till(3).test()

        shouldThrow<IllegalStateException> { till.send(Explode) }

        till.failure.shouldBeInstanceOf<Failure.Thrown>().throwable.message shouldBe "the till caught fire"
    }

    @Test
    fun `on threads, a raise stops the actor too`() {
        val answer = flock<Nothing, Any> {
            val till = spawn("till", till(3))
            till.tell(Sell(5))
            till.ask(1.minutes) { Count(it) }
        }

        answer shouldBe AskFailure.Stopped.left().right()
    }
}
