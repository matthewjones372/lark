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

    /** Materialises [run]. Called only once [start] has checked that every native value in it is this backend's. */
    @StreamSpi
    fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R>
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
 * The defect a run is refused with when it holds another backend's native value, found before anything
 * materialises. It names the builder and where it was written, because the run cannot start to say so.
 */
private fun Run<*, *>.refusedBy(backend: StreamBackend): Throwable? {
    if (admitted.get() === backend.key) return null
    val foreign = node.foreignTo(backend.key) ?: (end as? Owned)?.takeIf { it.owner !== backend.key }
    if (foreign == null) {
        admitted.set(backend.key)
        return null
    }
    val refusal = IllegalStateException(
        "lark-stream: ${foreign.builder}, built at ${foreign.at}, holds a ${foreign.owner} value, " +
            "and this run was started on ${backend.key}",
    )
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
 * The first native value in the tree that is not [key]'s, where a walk can see it. A `catchAll` recovery or
 * a `flatMap`'s inner stream is built only when it is needed, so a native value inside one is refused by the
 * backend then. It runs on every run, so it builds nothing on the way down.
 */
private fun Node.foreignTo(key: BackendKey): Owned? {
    if (this is Owned && owner !== key) return this
    for (child in children()) child.foreignTo(key)?.let { return it }
    return null
}
