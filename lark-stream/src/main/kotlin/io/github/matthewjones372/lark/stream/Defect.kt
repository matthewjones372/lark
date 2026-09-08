package io.github.matthewjones372.lark.stream

import java.util.concurrent.CompletionException

/**
 * What a defect is reported with beyond its cause: the operator, the element it was processing,
 * and the caller's line that built the operator.
 *
 * It rides along as a suppressed exception rather than as a wrapper, so that `Died` still carries
 * the class the caller's own code threw and the three facts print in the stack trace beside it.
 */
internal class Defect(operator: String, element: Any, at: String) :
    RuntimeException(facts(operator, element, at), null, false, false)

/** The sentence a defect is named by, in a log line and in a message the library raises alike. */
internal fun facts(operator: String, element: Any, at: String): String = "$operator died on $element, built at $at"

/**
 * The caller's [f], with the three facts attached to whatever it throws: the one wiring every
 * operator that runs caller code shares.
 *
 * [at] is read while the pipeline is described and the element is asked for its `toString` only
 * once something has already gone wrong, so a running stream pays for neither.
 */
internal fun <A : Any, B> guarded(operator: String, at: String, f: (A) -> B): (A) -> B =
    { a -> guard(operator, at, a, f) }

// The catch is as wide as the lambda, because a defect is everything the caller did not declare;
// which throwable is one is decided below.
@Suppress("TooGenericExceptionCaught")
private fun <A : Any, B> guard(operator: String, at: String, a: A, f: (A) -> B): B =
    try {
        f(a)
    } catch (thrown: Throwable) {
        throw thrown.describedBy(operator, a, at)
    }

/** The wrapper a declared failure travels in goes by untouched; everything else picks up the facts. */
internal fun Throwable.describedBy(operator: String, element: Any, at: String): Throwable {
    if (this !is DeclaredFailure) addSuppressed(Defect(operator, element, at))
    return this
}

/** What actually failed a stage: a CompletableFuture reports it wrapped in a CompletionException. */
internal fun Throwable.unwrapped(): Throwable = if (this is CompletionException) cause ?: this else this

private val walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

private val ownCode: String? = codeOf(Stream::class.java)

private fun codeOf(type: Class<*>): String? = type.protectionDomain?.codeSource?.location?.toString()

/**
 * The caller's line that built an operator, as `File.kt:12`.
 *
 * A frame is the library's own by where its class file came from rather than by its package,
 * because this library's own tests sit in its package and have to read as callers like anyone else.
 */
internal fun buildSite(): String =
    walker.walk { frames ->
        frames.filter { frame -> codeOf(frame.declaringClass) != ownCode }
            .findFirst()
            .map { frame -> "${frame.fileName}:${frame.lineNumber}" }
            .orElse("a line nobody could name")
    }
