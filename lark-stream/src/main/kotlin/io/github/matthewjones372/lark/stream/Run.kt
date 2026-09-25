@file:OptIn(SourceSeam::class)

package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.pekko.await
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.stream.Attributes
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.UniqueKillSwitch
import org.apache.pekko.stream.javadsl.Keep
import org.apache.pekko.stream.javadsl.RunnableGraph
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletionStage

/** A stream and the sink that ends it, described. Nothing runs until [run] or [start] names a system. */
class Run<out E, out R> internal constructor(
    // Invariant for the same reason Stream's source is: a Java generic read
    // from and never written to.
    internal val graph: RunnableGraph<org.apache.pekko.japi.Pair<UniqueKillSwitch, CompletionStage<@UnsafeVariance R>>>,
)

/**
 * A run in progress: the [exit] `run` would have answered, and a way to end it.
 *
 * `AutoCloseable` so that a graph node's release is `Running::close`.
 */
class Running<out E, out R> internal constructor(
    private val switch: UniqueKillSwitch,
    private val hooks: RunHooks,
    // Unsafe variance for Run's reason: a stage is only ever read from.
    val exit: CompletionStage<Exit<@UnsafeVariance E, @UnsafeVariance R>>,
) : AutoCloseable {

    /**
     * Ends the run now: downstream completes and upstream is cancelled, so the exit is `Done` with
     * whatever the sink had. What was between stages is dropped. On a run that already ended, nothing.
     *
     * A run with a source that drains, such as a Kafka consumer, is stopped by that drain instead, and ends
     * once what the source already sent has reached the sink.
     */
    fun stop() {
        if (!hooks.stop()) switch.shutdown()
    }

    /** [stop], then wait for the exit, so whatever the run used is still there until it has let go. */
    override fun close() {
        stop()
        exit.await()
    }
}

/**
 * A run described, to the sink named: the sink's materialised value is the run's.
 *
 * A materialised value that is not a `CompletionStage` is refused at the type: [run] would have nothing to wait on.
 */
fun <E, A : Any, M : Any> Stream<E, A>.runWith(sink: Sink<A, CompletionStage<M>>): Run<E, M> =
    // The switch sits right before the sink, so a stop is the sink completing with what it has.
    Run(source.viaMat(KillSwitches.single(), Keep.right()).toMat(sink, Keep.both()))

fun <E, A : Any> Stream<E, A>.runCollect(): Run<E, List<A>> = runWith(Sink.seq())

fun <E, A : Any, R : Any> Stream<E, A>.runFold(zero: R, f: (R, A) -> R): Run<E, R> =
    runWith(Sink.fold(zero) { total, a -> f(total, a) })

fun <E, R : Any> Run<E, R>.run(system: ClassicActorSystemProvider): CompletionStage<Exit<E, R>> =
    started(system, RunHooks()).second

/** The run materialised, as [run] does, with the handle that can end it before it ends itself. */
fun <E, R : Any> Run<E, R>.start(system: ClassicActorSystemProvider): Running<E, R> {
    val hooks = RunHooks()
    val (switch, exit) = started(system, hooks)
    return Running(switch, hooks, exit)
}

private fun <E, R : Any> Run<E, R>.started(
    system: ClassicActorSystemProvider,
    hooks: RunHooks,
): Pair<UniqueKillSwitch, CompletionStage<Exit<E, R>>> {
    // A sink authored in Java completes with null whatever its type argument says, and Kotlin would
    // hand that back as a Done whose value is typed non-null. The same guard Stream.fromStage has.
    val at = buildSite()
    val materialised = graph.addAttributes(Attributes.apply(RunHooksAttribute(hooks))).run(system)
    val exit = materialised.second()
        .checked { NullPointerException("$NULL_MATERIALISED, built at $at") }
        .thenApply<Exit<E, R>> { value -> Exit.Done(value) }
        .exceptionally { thrown -> thrown.asExit<E, R>().reportedTo(system) }
        .whenComplete { _, _ -> hooks.ended() }
    return materialised.first() to exit
}

private const val NULL_MATERIALISED = "lark-stream: run ended with null"

private fun <E, R> Throwable.asExit(): Exit<E, R> {
    val cause = unwrapped()
    return if (cause is DeclaredFailure) Exit.Failed(cause.declared()) else Exit.Died(cause)
}

/**
 * A defect is logged before the `Exit` carrying it is handed back, because the common way to lose
 * one is a stage nobody reads.
 */
private fun <E, R> Exit<E, R>.reportedTo(system: ClassicActorSystemProvider): Exit<E, R> {
    if (this is Exit.Died) system.classicSystem().log().error(cause, cause.oneLine())
    return this
}

/** One line: the three facts where the cause carries them apart, and then the cause itself. */
private fun Throwable.oneLine(): String {
    val defect = suppressed.filterIsInstance<Defect>().firstOrNull()
    return if (defect == null) "lark-stream: $this" else "lark-stream: ${defect.message}: $this"
}
