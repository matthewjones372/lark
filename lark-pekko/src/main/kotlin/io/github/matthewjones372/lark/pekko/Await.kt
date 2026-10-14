package io.github.matthewjones372.lark.pekko

import io.github.matthewjones372.lark.await
import scala.concurrent.Future
import scala.jdk.javaapi.FutureConverters

/** `lark`'s `CompletionStage.await()` for the Scala future half of Pekko's API answers with. */
fun <T> Future<T>.await(): T = FutureConverters.asJava(this).await()
