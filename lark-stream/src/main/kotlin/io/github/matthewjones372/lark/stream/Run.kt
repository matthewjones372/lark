package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.pekko.await
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.stream.UniqueKillSwitch
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletionStage

/** A stream and the sink that ends it, described. Nothing runs until [run] or [start] names a system. */
class Run<out E, out R> @StreamSpi constructor(
    @property:StreamSpi val node: Node,
    @property:StreamSpi val end: End,
) {

    /** What a backend compiled the run to, kept: every run materialises the same blueprint. */
    @property:StreamSpi
    val compiled = CompileCache()
}

/**
 * A run described, to the sink named: the sink's materialised value is the run's.
 *
 * A materialised value that is not a `CompletionStage` is refused at the type: [run] would have nothing to wait on.
 */
fun <E, A : Any, M : Any> Stream<E, A>.runWith(sink: Sink<A, CompletionStage<M>>): Run<E, M> =
    Run(node, End.Native(sink, Pekko, "runWith", buildSite()))

fun <E, A : Any> Stream<E, A>.runCollect(): Run<E, List<A>> = Run(node, End.Collect)

fun <E, A : Any, R : Any> Stream<E, A>.runFold(zero: R, f: (R, A) -> R): Run<E, R> =
    Run(node, End.Fold(zero, f.erased()))

/** Pekko Streams on [system], the backend lark-stream started on. */
class PekkoStreams(private val system: ClassicActorSystemProvider) : StreamBackend {

    @StreamSpi
    override val key: BackendKey get() = Pekko

    @StreamSpi
    override fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R> {
        val (switch, exit) = run.started(system)
        return PekkoRunning(switch, exit)
    }
}

/** [run] on [PekkoStreams], which is what naming a system has always meant. */
fun <E, R : Any> Run<E, R>.run(system: ClassicActorSystemProvider): CompletionStage<Exit<E, R>> =
    run(PekkoStreams(system))

fun <E, R : Any> Run<E, R>.start(system: ClassicActorSystemProvider): Running<E, R> = start(PekkoStreams(system))

private class PekkoRunning<E, R>(
    private val switch: UniqueKillSwitch,
    override val exit: CompletionStage<Exit<E, R>>,
) : Running<E, R> {

    override fun stop() = switch.shutdown()

    override fun close() {
        stop()
        exit.await()
    }
}

private fun <E, R : Any> Run<E, R>.started(
    system: ClassicActorSystemProvider,
): Pair<UniqueKillSwitch, CompletionStage<Exit<E, R>>> {
    // A sink authored in Java completes with null whatever its type argument says, and Kotlin would
    // hand that back as a Done whose value is typed non-null. The same guard Stream.fromStage has.
    val at = buildSite()
    val materialised = graph.run(system)

    @Suppress("UNCHECKED_CAST")
    val exit = (materialised.second() as CompletionStage<R>)
        .checked { NullPointerException("$NULL_MATERIALISED, built at $at") }
        .thenApply<Exit<E, R>> { value -> Exit.Done(value) }
        .exceptionally { thrown -> thrown.asExit<E, R>().reportedTo(system) }
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
