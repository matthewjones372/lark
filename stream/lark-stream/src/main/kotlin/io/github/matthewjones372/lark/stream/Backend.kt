package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** What runs a described [Run]: Pekko Streams, forks, or a test's own clock. The description never says which. */
interface StreamBackend {

    /** The key this backend's native values are tagged with. */
    @StreamSpi
    val key: BackendKey

    /** Materialises [run]. Called only once [start] has checked the whole tree against [key] and [runs]. */
    @StreamSpi
    fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R>

    /** Whether this backend can run [node]'s operator. [start] refuses a run holding one it cannot, by name. */
    @StreamSpi
    fun runs(node: Node): Boolean = true
}

/**
 * A run in progress: the [exit] `run` would have answered, and a way to end it.
 *
 * `AutoCloseable` so that a graph node's release is `Running::close`.
 */
interface Running<out E, out R> : AutoCloseable {

    // Unsafe variance because a stage is only ever read from.
    val exit: CompletionStage<Exit<@UnsafeVariance E, @UnsafeVariance R>>

    /**
     * Ends the run now: downstream completes and upstream is cancelled, so the exit is `Done` with
     * whatever the sink had. What was between stages is dropped. On a run that already ended, nothing.
     */
    fun stop()

    /** [stop], then wait for the exit, so whatever the run used is still there until it has let go. */
    override fun close()
}

/** The run materialised on [backend], with the handle that can end it before it ends itself. */
fun <E, R : Any> Run<E, R>.start(backend: StreamBackend): Running<E, R> =
    refusedBy(backend)?.let(::Refused) ?: backend.materialise(this)

fun <E, R : Any> Run<E, R>.run(backend: StreamBackend): CompletionStage<Exit<E, R>> = start(backend).exit

/**
 * The defect a run is refused with when it holds another backend's native value or an operator [backend]
 * cannot run, found before anything materialises. It names the operator and where it was written, because
 * the run cannot start to say so.
 */
private fun Run<*, *>.refusedBy(backend: StreamBackend): Throwable? {
    if (admitted.get() === backend.key) return null
    val reason = node.refusalOn(backend) ?: (end as? Owned)?.foreignTo(backend)
    if (reason == null) {
        admitted.set(backend.key)
        return null
    }
    val refusal = IllegalStateException("lark-stream: $reason")
    // A defect is logged before the Exit carrying it is handed back: the stage may be one nobody reads.
    logger.get().log(LogLine(LogLevel.Error, refusal.message.orEmpty(), clock.get().now(), refusal))
    return refusal
}

/** A run that never started: it ended `Died` before any element flowed, so there is nothing to stop. */
private class Refused<E, R>(cause: Throwable) : Running<E, R> {
    override val exit: CompletionStage<Exit<E, R>> = CompletableFuture.completedFuture(Exit.Died(cause))

    override fun stop() = Unit

    override fun close() = Unit
}

/**
 * Why [backend] cannot run the first node in the tree it cannot, where a walk can see it. A `catchAll`
 * recovery or a `flatMap`'s inner stream is built only when it is needed, so a node inside one is refused
 * by the backend then. It builds nothing on the way down.
 */
private fun Node.refusalOn(backend: StreamBackend): String? {
    val here = when {
        this is Owned -> foreignTo(backend)
        !backend.runs(this) -> "${named()} is not something ${backend.key} runs"
        else -> null
    }
    if (here != null) return here
    for (child in children()) child.refusalOn(backend)?.let { return it }
    return null
}

private fun Node.named(): String = site?.let { "$operator, built at $it," } ?: operator

private fun Owned.foreignTo(backend: StreamBackend): String? =
    if (owner === backend.key) {
        null
    } else {
        "$builder, built at $at, holds a $owner value, and this run was started on ${backend.key}"
    }
