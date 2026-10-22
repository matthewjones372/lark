package io.github.matthewjones372.lark.fail

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** The scope a declared failure of type [E] leaves through; [attempt] opens one and answers with how it ended. */
class Fail<in E> internal constructor(private val openedIn: String) {

    @Volatile
    private var closed = false

    internal fun failWith(error: E): Nothing {
        // A Fail that escaped its attempt, in a lambda or a fork still running, has nobody left to answer.
        check(!closed) { "fail was called on a scope opened in $openedIn after its attempt had returned" }
        throw Failed(error, this)
    }

    internal fun close() {
        closed = true
    }
}

/**
 * How [fail] leaves the block. It extends Throwable rather than Exception so that `catch (e: Exception)` in
 * the block cannot swallow it, and records no stack trace because nothing ever prints it.
 */
internal class Failed(val error: Any?, val scope: Fail<*>) : Throwable(null, null, false, false)

/** Runs [block], answering with its value on the right, or on the left with the error it failed with. */
fun <E, A> attempt(block: context(Fail<E>) () -> A): Either<E, A> {
    val scope = Fail<E>(block.javaClass.name)
    return try {
        Either.Right(block(scope))
    } catch (failed: Failed) {
        if (failed.scope !== scope) throw failed
        // Only failWith on this same Fail<E> threw it, so the error is an E.
        @Suppress("UNCHECKED_CAST")
        Either.Left(failed.error as E)
    } finally {
        scope.close()
    }
}

/** Runs [block], answering a failure with [recover] instead. */
inline fun <E, A> recover(noinline block: context(Fail<E>) () -> A, recover: (E) -> A): A =
    attempt(block).getOrElse(recover)

context(scope: Fail<E>)
fun <E> fail(error: E): Nothing = scope.failWith(error)

context(scope: Fail<E>)
fun <E, A> Either<E, A>.bind(): A = fold({ scope.failWith(it) }, { it })

@OptIn(ExperimentalContracts::class)
context(scope: Fail<E>)
inline fun <E> ensure(condition: Boolean, error: () -> E) {
    contract { returns() implies condition }
    if (!condition) fail(error())
}

@OptIn(ExperimentalContracts::class)
context(scope: Fail<E>)
inline fun <E, A : Any> ensureNotNull(value: A?, error: () -> E): A {
    contract { returns() implies (value != null) }
    return value ?: fail(error())
}
