package io.github.matthewjones372.lark.pelican

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.ensure
import arrow.core.right
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.handledOrFail
import io.github.matthewjones372.pelican.responseHeader
import io.github.matthewjones372.pelican.test.ApiClient
import io.github.matthewjones372.pelican.test.pekko.inMemory
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicReference

data class User(val id: Long)

data class Problem(val code: String)

private val userId = pathParam<Long>("userId")

private val requestId = responseHeader<String>("X-Request-Id", description = "Correlates this answer with the log")

private val noSuchUser = errorJson<Problem>(404, "No user with that id")

private val getUser = endpoint(userId) {
    get("users" / userId)
    operationId = "getUser"
    emits(requestId)
    json<User>() orFail noSuchUser
}

/** The shape a service written in `Raise` answers a handler with. */
private fun findUser(id: Long): Either<Problem, User> =
    if (id == 1L) User(id).right() else Problem("no user $id").left()

/**
 * One app for one test, through Pelican's typed client. The error hook is
 * empty because the throwing test throws on purpose, and the stack trace the
 * interpreter logs instead reads as a failure in the build output.
 */
private fun <T> served(vararg bound: ServerEndpoint, block: (ApiClient) -> T): T =
    api(endpoints = bound.toList(), codecs = JacksonCodecs) { onError { _, _, _ -> } }
        .inMemory("lark-rising")
        .use(block)

class RisingTest {

    @Test
    fun `a return is the declared success`() {
        served(getUser handledRaising { id -> User(id) }) { app ->
            app.call(getUser, 1L) shouldBe User(1)
        }
    }

    @Test
    fun `a raise is the single declared failure, under the status it declared`() {
        served(
            getUser handledRaising { id ->
                if (id == 1L) User(id) else raise(Problem("no user $id"))
            },
        ) { app ->
            app.outcome(getUser, 404L)
                .shouldBeInstanceOf<Outcome.Err<Problem>>()
                .error shouldBe Problem("no user 404")

            app.response(getUser, 404L).status shouldBe 404
        }
    }

    @Test
    fun `bind on a Left from the service is that failure`() {
        served(getUser handledRaising { id -> findUser(id).bind() }) { app ->
            app.call(getUser, 1L) shouldBe User(1)
            app.response(getUser, 2L).status shouldBe 404
        }
    }

    @Test
    fun `ensure is that failure too`() {
        served(
            getUser handledRaising { id ->
                ensure(id == 1L) { Problem("no user $id") }
                User(id)
            },
        ) { app ->
            app.outcome(getUser, 2L)
                .shouldBeInstanceOf<Outcome.Err<Problem>>()
                .error shouldBe Problem("no user 2")
        }
    }

    @Test
    fun `the body runs on a virtual thread of its own, not on the caller's`() {
        val ran = AtomicReference<Thread>()

        served(
            getUser handledRaising { id ->
                ran.set(Thread.currentThread())
                User(id)
            },
        ) { app ->
            app.call(getUser, 1L) shouldBe User(1)
        }

        val body = ran.get()
        withClue("the handler ran on $body") { body.isVirtual shouldBe true }
        body shouldNotBe Thread.currentThread()
    }

    @Test
    fun `a throw is the interpreter's 500, as a throwing handler is`() {
        val raised = served(getUser handledRaising { error("the database is on fire") }) { app ->
            app.response(getUser, 1L).status
        }
        val thrown = served(getUser handledOrFail { error("the database is on fire") }) { app ->
            app.response(getUser, 1L).status
        }

        raised shouldBe 500
        raised shouldBe thrown
    }

    @Test
    fun `setHeader from the body reaches the response`() {
        served(
            getUser handledRaising { id ->
                setHeader(requestId, "req-$id")
                User(id)
            },
        ) { app ->
            app.response(getUser, 1L).header("X-Request-Id") shouldBe "req-1"
        }
    }

    @Test
    fun `the request's Params are in reach, for what the endpoint never declared`() {
        served(
            getUser handledRaising { id ->
                params.setRawHeader("X-Debug-Handler", "rising")
                User(id)
            },
        ) { app ->
            app.response(getUser, 1L).header("X-Debug-Handler") shouldBe "rising"
        }
    }
}
