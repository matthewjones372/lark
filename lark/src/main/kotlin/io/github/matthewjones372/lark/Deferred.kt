package io.github.matthewjones372.lark

/** A fork's answer, asked for once it is wanted; not a `Future`, because there is nothing to cancel by hand. */
interface Deferred<T> {
    /** Joins the fork, then returns its value, raises its error into the awaiting scope, or rethrows its throwable. */
    fun await(): T
}
