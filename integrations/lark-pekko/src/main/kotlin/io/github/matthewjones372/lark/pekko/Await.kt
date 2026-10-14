package io.github.matthewjones372.lark.pekko

import scala.concurrent.Future
import scala.jdk.javaapi.FutureConverters
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

/**
 * Waits on the calling thread for this stage and answers with its value: on a virtual thread that parks it
 * and nothing else. A failure arrives as the cause itself, and an interrupt cancels the stage on the way out.
 */
fun <T> CompletionStage<T>.await(): T {
    val future = toCompletableFuture()
    return try {
        future.get()
    } catch (failed: ExecutionException) {
        // `get` wraps whatever completed the stage, peeling a CompletionException as it goes; what the
        // caller declared and catches is the cause, not either wrapper.
        throw failed.cause ?: failed
    } catch (stop: InterruptedException) {
        // Nobody is left to read the answer, so the stage is told to stop rather than run on unwatched.
        future.cancel(true)
        throw stop
    }
}

/** The same wait for the Scala future half of Pekko's API answers with. */
fun <T> Future<T>.await(): T = FutureConverters.asJava(this).await()
