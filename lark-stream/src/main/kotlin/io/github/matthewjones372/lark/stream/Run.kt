package io.github.matthewjones372.lark.stream

import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.stream.javadsl.Keep
import org.apache.pekko.stream.javadsl.RunnableGraph
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage

/** A stream and the sink that ends it, described. Nothing runs until [run] names a system. */
class Run<out E, out R> internal constructor(
    // Invariant for the same reason Stream's source is: a Java generic read
    // from and never written to.
    internal val graph: RunnableGraph<CompletionStage<@UnsafeVariance R>>,
)

/**
 * A run described, to the sink named: the sink's materialised value is the run's.
 *
 * A materialised value that is not a `CompletionStage` is refused at the type: [run] would have nothing to wait on.
 */
fun <E, A : Any, M> Stream<E, A>.runWith(sink: Sink<A, CompletionStage<M>>): Run<E, M> =
    Run(source.toMat(sink, Keep.right()))

fun <E, A : Any> Stream<E, A>.runCollect(): Run<E, List<A>> = runWith(Sink.seq())

fun <E, A : Any, R> Stream<E, A>.runFold(zero: R, f: (R, A) -> R): Run<E, R> =
    runWith(Sink.fold(zero) { total, a -> f(total, a) })

fun <E, R> Run<E, R>.run(system: ClassicActorSystemProvider): CompletionStage<Exit<E, R>> =
    graph.run(system)
        .thenApply<Exit<E, R>> { value -> Exit.Done(value) }
        .exceptionally { thrown -> thrown.asExit() }

private fun <E, R> Throwable.asExit(): Exit<E, R> {
    // A CompletableFuture reports what failed it wrapped in a CompletionException.
    val cause = if (this is CompletionException) this.cause ?: this else this
    return if (cause is DeclaredFailure) Exit.Failed(cause.declared()) else Exit.Died(cause)
}
