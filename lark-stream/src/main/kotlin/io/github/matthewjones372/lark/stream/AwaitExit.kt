package io.github.matthewjones372.lark.stream

import arrow.core.raise.Raise
import io.github.matthewjones372.lark.pekko.await
import java.util.concurrent.CompletionStage

/**
 * Waits for a run on the calling thread and answers with what it ended with: `Done` is the value,
 * `Failed` raises the error here, and `Died` throws the cause nobody declared.
 *
 * The wait is `lark-pekko`'s, so an interrupt cancels the run's stage rather than abandoning it. Kotlin
 * has no receiver to spare for a `Raise<E>` and a `CompletionStage` at once, so the stage is the
 * argument: `awaitExit(pipeline.run(system))` inside an `either { }`.
 */
fun <E, R : Any> Raise<E>.awaitExit(stage: CompletionStage<Exit<E, R>>): R = when (val exit = stage.await()) {
    is Exit.Done -> exit.value
    is Exit.Failed -> raise(exit.error)
    is Exit.Died -> throw exit.cause
}
