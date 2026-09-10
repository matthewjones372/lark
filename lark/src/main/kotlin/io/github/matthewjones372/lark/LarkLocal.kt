package io.github.matthewjones372.lark

/**
 * A binding a fork inherits: what the opening thread had bound is what its branches read.
 *
 * A `ThreadLocal` is empty on a thread nothing bound it on, and every combinator here opens one.
 */
class LarkLocal<A> internal constructor(private val initial: () -> A) {

    fun get(): A {
        val bound = Bindings.snapshot()
        // Only `locally` writes the map, and it writes an A under this key.
        @Suppress("UNCHECKED_CAST")
        return if (bound.containsKey(this)) bound[this] as A else initial()
    }

    /** Runs [block] with [value] bound, and puts back what was bound before on the way out. */
    fun <B> locally(value: A, block: () -> B): B = Bindings.under(Bindings.snapshot() + (this to value), block)
}

/** A [LarkLocal] answering with [initial] wherever nothing has been bound. */
fun <A> larkLocal(initial: () -> A): LarkLocal<A> = LarkLocal(initial)

internal object Bindings {

    private val current = ThreadLocal<Map<LarkLocal<*>, Any?>>()

    fun snapshot(): Map<LarkLocal<*>, Any?> = current.get() ?: emptyMap()

    fun <B> under(bound: Map<LarkLocal<*>, Any?>, block: () -> B): B {
        val before = current.get()
        current.set(bound)
        return try {
            block()
        } finally {
            if (before == null) current.remove() else current.set(before)
        }
    }
}
