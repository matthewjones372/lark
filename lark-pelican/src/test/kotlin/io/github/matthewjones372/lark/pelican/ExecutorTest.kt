package io.github.matthewjones372.lark.pelican

import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.ServerEndpoint
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.ok
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class Ticket(val id: Long)

private const val THREAD_NAME = "lark-caller-supplied"

/** Generous: every wait here is for something another thread has already been told to do. */
private const val PATIENCE_SECONDS = 10L

private val ticketId = pathParam<Long>("ticketId")

private val noSuchTicket = errorJson<Problem>(404, "No ticket with that id")

private val getTicket = endpoint(ticketId) {
    get("tickets" / ticketId)
    operationId = "getTicket"
    json<Ticket>() orFail noSuchTicket
}

/** One platform thread with a name, so a body can say where it ran. */
private fun oneThread(): ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, THREAD_NAME) }

/**
 * What an interpreter hands a bound endpoint. Built by hand because these tests
 * hold the stage the handler answers with, which a request through the client
 * never exposes.
 */
private fun paramsFor(id: Long): Params =
    Params(getTicket.inputs.inject(id), underlying = null, endpoint = getTicket)

class ExecutorTest {

    @Test
    fun `the body runs on the executor it was given, and not on a virtual thread`() {
        val ran = AtomicReference<Thread>()
        val executor = oneThread()

        try {
            served(
                getTicket.handledRaising(on = executor) { id ->
                    ran.set(Thread.currentThread())
                    Ticket(id)
                },
            ) { app ->
                app.call(getTicket, 1L) shouldBe Ticket(1)
            }
        } finally {
            executor.shutdownNow()
        }

        val body = ran.get()
        withClue("the handler ran on $body") {
            body.name shouldBe THREAD_NAME
            body.isVirtual shouldBe false
        }
    }

    @Test
    fun `cancelling the stage interrupts the body, and the cancelled stage stays cancelled`() {
        val executor = oneThread()
        val started = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val ran = AtomicReference<Thread>()

        val bound = getTicket.handledRaising(on = executor) { id ->
            ran.set(Thread.currentThread())
            started.countDown()
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1))
            } catch (_: InterruptedException) {
                interrupted.countDown()
            }
            Ticket(id)
        }

        val stage = bound.invoke(paramsFor(1L)).toCompletableFuture()
        started.await(PATIENCE_SECONDS, TimeUnit.SECONDS) shouldBe true
        stage.cancel(true) shouldBe true

        withClue("the body was still blocked when the stage was cancelled") {
            interrupted.await(PATIENCE_SECONDS, TimeUnit.SECONDS) shouldBe true
        }

        // The body returns a Ticket after the interrupt; completing a cancelled
        // stage does nothing, so what the caller sees is still the cancellation.
        executor.shutdown()
        executor.awaitTermination(PATIENCE_SECONDS, TimeUnit.SECONDS) shouldBe true
        ran.get().isAlive shouldBe false
        stage.isCancelled shouldBe true
    }

    @Test
    fun `a cancel after the body has finished interrupts nothing`() {
        val executor = oneThread()

        try {
            val bound = getTicket.handledRaising(on = executor) { id -> Ticket(id) }
            val stage = bound.invoke(paramsFor(2L)).toCompletableFuture()

            stage.get(PATIENCE_SECONDS, TimeUnit.SECONDS) shouldBe ok(Ticket(2))

            withClue("a completed stage is past cancelling, so it keeps the body's answer") {
                stage.cancel(true) shouldBe false
                stage.isCancelled shouldBe false
            }

            // The next task on that one thread, which the interrupt would have
            // reached had the reference to the body's thread outlived the body.
            val later = CompletableFuture<Boolean>()
            executor.execute { later.complete(Thread.currentThread().isInterrupted) }
            later.get(PATIENCE_SECONDS, TimeUnit.SECONDS) shouldBe false
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `both forms complete the stage on the body's thread, not the caller's`() {
        val executor = oneThread()

        try {
            completedOn { f -> getTicket.handledRaising(on = executor, f = f) }.name shouldBe THREAD_NAME
            completedOn { f -> getTicket handledRaising f }.isVirtual shouldBe true
        } finally {
            executor.shutdownNow()
        }
    }
}

/**
 * Which thread ran a dependent of the handler's stage. The body waits on a gate
 * until the dependent is registered: one registered after the stage completed
 * runs on the thread that registers it, and would make the claim by accident.
 */
private fun completedOn(bind: (Rising<Problem>.(Long) -> Ticket) -> ServerEndpoint): Thread {
    val gate = CountDownLatch(1)
    val bound = bind { id ->
        gate.await(PATIENCE_SECONDS, TimeUnit.SECONDS)
        Ticket(id)
    }

    val stage = bound.invoke(paramsFor(3L)).toCompletableFuture()
    val completedOn = CompletableFuture<Thread>()
    stage.whenComplete { _, _ -> completedOn.complete(Thread.currentThread()) }
    gate.countDown()

    return completedOn.get(PATIENCE_SECONDS, TimeUnit.SECONDS)
}
