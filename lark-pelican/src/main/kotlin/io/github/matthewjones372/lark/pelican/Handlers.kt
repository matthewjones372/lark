package io.github.matthewjones372.lark.pelican

import io.github.matthewjones372.pelican.Endpoint
import io.github.matthewjones372.pelican.Outcome
import io.github.matthewjones372.pelican.ServerEndpoint
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/**
 * Binds an endpoint to a handler written in Arrow's `Raise`, on a virtual
 * thread of its own — so the body may block, and a `raise` never crosses a
 * thread because the boundary opens and closes on that one.
 */
infix fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledRaising(
    f: Rising<E>.(I) -> T,
): ServerEndpoint = raising({ body -> Thread.ofVirtual().start(body) }, f)

/**
 * As [handledRaising], on an executor the caller owns — a direct one for a
 * test, or the pool a service already sizes against the resource it guards.
 */
fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.handledRaising(
    on: Executor,
    f: Rising<E>.(I) -> T,
): ServerEndpoint = raising(on::execute, f)

/**
 * What the two forms share: everything except which thread [start] runs the
 * body on.
 */
private fun <I, E : Any, T : Any> Endpoint<I, Outcome<E, T>>.raising(
    start: (Runnable) -> Unit,
    f: Rising<E>.(I) -> T,
): ServerEndpoint = ServerEndpoint(this) { p ->
    // Extracted where Pelican's own binders extract it: on the calling thread,
    // so a failure reading the inputs is the interpreter's as it is today.
    val input = inputs.extract(p)
    val stage = CompletableFuture<Any?>()
    start { runRising(stage, p) { f(input) } }
    stage
}
