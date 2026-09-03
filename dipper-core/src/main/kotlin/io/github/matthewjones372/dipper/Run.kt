package io.github.matthewjones372.dipper

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

fun <E, A : Any> Stream<E, A>.runCollect(): Run<E, List<A>> = Run(source.toMat(Sink.seq(), Keep.right()))

fun <E, A : Any, R> Stream<E, A>.runFold(zero: R, f: (R, A) -> R): Run<E, R> =
    Run(source.toMat(Sink.fold(zero) { total, a -> f(total, a) }, Keep.right()))

fun <E, R> Run<E, R>.run(system: ClassicActorSystemProvider): CompletionStage<Exit<E, R>> =
    graph.run(system)
        .thenApply<Exit<E, R>> { value -> Exit.Done(value) }
        .exceptionally { thrown -> thrown.asExit() }

/**
 * The cast is unchecked because the error was erased into [DeclaredFailure] on
 * its way through Pekko's failure channel, where a `Throwable` is all the
 * channel can carry; the stream's own `E` is what says what came back.
 */
@Suppress("UNCHECKED_CAST")
private fun <E, R> Throwable.asExit(): Exit<E, R> {
    // A CompletableFuture reports what failed it wrapped in a CompletionException.
    val cause = if (this is CompletionException) this.cause ?: this else this
    return if (cause is DeclaredFailure) Exit.Failed(cause.error as E) else Exit.Died(cause)
}
