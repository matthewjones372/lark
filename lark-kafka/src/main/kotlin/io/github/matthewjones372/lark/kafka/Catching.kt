package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.nonFatalOrThrow

/** What [block] returned, or on the left what it threw that a caller may handle; anything fatal goes on up. */
@Suppress("TooGenericExceptionCaught") // a client's call can throw anything, and each throw is the answer
internal inline fun <A> catching(block: () -> A): Either<Throwable, A> =
    try {
        Either.Right(block())
    } catch (thrown: Throwable) {
        Either.Left(thrown.nonFatalOrThrow())
    }
