// In lark-stream's package, beside lark-kafka's runCollect and runFold guards, so importing lark-stream-pekko's
// runWith imports this too.
package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.kafka.Committed
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletionStage

private const val COMMIT = "A stream of Committed records ends on runCommitting, or its offsets are never committed."

@Deprecated(COMMIT, level = DeprecationLevel.ERROR)
@Suppress("UnusedParameter") // The signature is what refuses the call; nothing can reach the body.
fun <E, A : Any, M : Any> Stream<E, Committed<A>>.runWith(sink: Sink<Committed<A>, CompletionStage<M>>): Run<E, M> =
    error(COMMIT)
