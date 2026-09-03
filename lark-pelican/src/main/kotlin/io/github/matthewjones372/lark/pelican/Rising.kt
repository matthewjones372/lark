package io.github.matthewjones372.lark.pelican

import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.Params
import io.github.matthewjones372.pelican.ResponseHeader
import io.github.matthewjones372.pelican.ok
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicReference

/**
 * The scope one request's handler runs in: Arrow's [Raise] over the failure the
 * endpoint declared, carrying that request's [Params].
 */
class Rising<E : Any> internal constructor(
    /**
     * Raises the whole [Outcome.Err] rather than the error, so a form that
     * names which declared failure is meant has somewhere to put it.
     */
    private val boundary: Raise<Outcome.Err<E>>,
    val params: Params,
) : Raise<E> {

    /**
     * The single declared failure, which is what Pelican's bare `err(error)`
     * means: which failure that is gets resolved where the response is written.
     */
    override fun raise(r: E): Nothing = boundary.raise(Outcome.Err(null, r))

    /**
     * The failure a declaration names — `raise(forbidden(OrderHidden(id)))`.
     * Calling the declaration is what fixes the status, which is what an
     * endpoint declaring several failures needs from its handler.
     */
    fun raise(failure: Outcome<E, Nothing>): Nothing = when (failure) {
        is Outcome.Err -> boundary.raise(failure)

        // Unreachable, and the compiler agrees: a success here carries a value
        // of type Nothing, which nothing can have produced.
        is Outcome.Ok -> failure.value
    }

    /** [Params.setHeader], so a declared response header needs no second receiver. */
    fun <T : Any> setHeader(header: ResponseHeader<T>, value: T) = params.setHeader(header, value)
}

/**
 * Runs [body] on the calling thread and answers [stage] with what it did: a
 * return with `ok`, a raise with the failure it named, a throw exceptionally.
 * A [stage] cancelled while the body runs interrupts it.
 *
 * The only `catch (t: Throwable)` in the module, and it catches everything
 * rather than what Arrow calls non-fatal: a throwable that escaped here would
 * end the request's thread with nothing completing the stage, and the caller
 * would wait for a response that is never written.
 */
internal fun <E : Any, T : Any> runRising(
    stage: CompletableFuture<Any?>,
    params: Params,
    body: Rising<E>.() -> T,
) {
    // Interrupt is the only cancellation the JDK has, and an executor hands out
    // no thread of its own, so the body publishes the one it was given. Taken
    // rather than read, so a cancel and the body's own end cannot both act on
    // it, and cleared at the end so a late cancel finds nothing to interrupt.
    val running = AtomicReference<Thread?>(Thread.currentThread())
    stage.whenComplete { _, failure ->
        if (failure is CancellationException) running.getAndSet(null)?.interrupt()
    }

    try {
        either<Outcome.Err<E>, T> { Rising(this, params).body() }
            .fold({ failure -> stage.complete(failure) }, { value -> stage.complete(ok(value)) })
    } catch (t: Throwable) {
        stage.completeExceptionally(t)
    } finally {
        running.set(null)
        // An interrupt that arrived as the body was leaving is nobody's news
        // now; left set, it would end the executor's next task instead.
        Thread.interrupted()
    }
}
