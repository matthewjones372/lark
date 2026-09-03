package io.github.matthewjones372.lark.pelican

import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.UndeclaredResponse
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.err
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.of
import io.github.matthewjones372.pelican.optional
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.responseHeader
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

data class Order(val id: Long)

/** Two failures under one supertype, which is what `orFail` widens `E` to. */
sealed interface OrderError {
    data class NoSuchOrder(val id: Long) : OrderError

    data class OrderHidden(val id: Long) : OrderError
}

private val orderId = pathParam<Long>("orderId")

private val retryAfter = responseHeader<Long>("Retry-After", "How long the caller should wait").optional()

private val noSuchOrder = errorJson<OrderError.NoSuchOrder>(404, "No order with that id")

private val forbidden = errorJson<OrderError.OrderHidden>(403, "The order is not the caller's", retryAfter)

private val getOrder = endpoint(orderId) {
    get("orders" / orderId)
    operationId = "getOrder"
    json<Order>().orFail(noSuchOrder, forbidden)
}

class NamingTest {

    @Test
    fun `each declared failure is answered under the status its declaration fixed`() {
        served(
            getOrder handledRaising { id ->
                when (id) {
                    403L -> raise(forbidden(OrderError.OrderHidden(id)))
                    404L -> raise(noSuchOrder(OrderError.NoSuchOrder(id)))
                    else -> Order(id)
                }
            },
        ) { app ->
            app.outcome(getOrder, 403L)
                .shouldBeInstanceOf<Outcome.Err<OrderError>>()
                .error shouldBe OrderError.OrderHidden(403)
            app.response(getOrder, 403L).status shouldBe 403

            app.outcome(getOrder, 404L)
                .shouldBeInstanceOf<Outcome.Err<OrderError>>()
                .error shouldBe OrderError.NoSuchOrder(404)
            app.response(getOrder, 404L).status shouldBe 404

            app.call(getOrder, 1L) shouldBe Order(1)
        }
    }

    @Test
    fun `a bare raise with two failures declared is refused in the words bare err is refused in`() {
        val raised = AtomicReference<Throwable>()
        val returned = AtomicReference<Throwable>()

        val fromRaise = served(
            getOrder handledRaising { id -> raise(OrderError.NoSuchOrder(id)) },
            onFailure = raised::set,
        ) { app -> app.response(getOrder, 7L).status }

        val fromErr = served(
            getOrder handledOrFail { id -> err(OrderError.NoSuchOrder(id)) },
            onFailure = returned::set,
        ) { app -> app.response(getOrder, 7L).status }

        fromRaise shouldBe 500
        fromRaise shouldBe fromErr

        raised.get().shouldBeInstanceOf<UndeclaredResponse>().message shouldBe
            returned.get().shouldBeInstanceOf<UndeclaredResponse>().message
    }

    @Test
    fun `a header passed to the declaration reaches the response`() {
        served(
            getOrder handledRaising { id -> raise(forbidden(OrderError.OrderHidden(id), retryAfter of 30L)) },
        ) { app ->
            app.response(getOrder, 5L).header("Retry-After") shouldBe "30"
        }
    }
}
