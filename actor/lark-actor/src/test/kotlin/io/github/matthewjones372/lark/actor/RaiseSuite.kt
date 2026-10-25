package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.attempt
import io.github.matthewjones372.lark.fail.bind
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.shouldBeRight
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.resource
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
        is Sell -> if (message.count > left) fail(OutOfStock) else become(left - message.count)

        is Settle -> become(left - message.owed.bind())

        is Try -> {
            val attempt = attempt { if (message.count > left) fail(OutOfStock) else left - message.count }
            become(attempt.getOrNull() ?: left)
        }

        is Count -> {
            message.reply(left)
            stay()
        }

        Explode -> error("the till caught fire")
    }
}

object RaiseSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a raise stops the actor, and is its failure") { _ ->
            val till = till(3).test()

            till.send(Sell(5))

            (till.stopped shouldBe true) and (till.failure shouldBe Failure.Declared(OutOfStock))
        }

        test("a bind on a Left is a raise") { _ ->
            val till = till(3).test()

            till.send(Settle(OutOfStock.left()))

            till.failure shouldBe Failure.Declared(OutOfStock)
        }

        test("a raise inside the step's own either is that either's, and the actor carries on") { _ ->
            val till = till(3).test()

            till.send(Try(5))
            till.send(Settle(2.right()))

            (till.stopped shouldBe false) and (till.state shouldBe 1)
        }

        test("a throw is a Thrown failure, and still reaches the test") { _ ->
            val till = till(3).test()

            val thrown = shouldThrow<IllegalStateException> { till.send(Explode) }

            thrown and till.failure.shouldBeInstanceOf<Failure.Thrown> { failure ->
                failure.throwable.message shouldBe "the till caught fire"
            }
        }

        test("on threads, a raise stops the actor too") { _ ->
            val answer = flock<Nothing, Any> {
                val till = spawn("till", till(3))
                till.tell(Sell(5))
                till.ask(1.minutes) { Count(it) }
            }

            answer shouldBeRight AskFailure.Stopped.left()
        }
    }
}
