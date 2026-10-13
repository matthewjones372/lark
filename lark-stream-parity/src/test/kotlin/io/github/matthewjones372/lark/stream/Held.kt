package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.flock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/**
 * One flock held open for the whole test JVM, for the backends that run on one. An invocation cannot sit inside a
 * flock's scope, so a daemon thread holds it, and the JVM's exit ends it.
 */
internal val heldFlock: Flock<Nothing> by lazy {
    val opened = AtomicReference<Flock<Nothing>>()
    val ready = CountDownLatch(1)
    Thread.ofPlatform().daemon().start {
        flock<Nothing, Unit> {
            opened.set(this)
            ready.countDown()
            CountDownLatch(1).await()
        }
    }
    ready.await()
    opened.get()
}
