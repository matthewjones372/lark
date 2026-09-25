package io.github.matthewjones372.lark.stream

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Every [Opened] resource of one run: woken by its `stop()`, closed when its loop ends. A source finds its
 * run's through [here] as it is pulled for the first time, which is on the run's own thread.
 */
internal class Resources {

    private val opened = CopyOnWriteArrayList<Opened>()
    private val attempts = CopyOnWriteArrayList<Resources>()

    @Volatile
    private var stopping = false

    /** [resource], registered; woken at once if the run was already stopping when it opened. */
    fun add(resource: Opened): Opened {
        opened += resource
        if (stopping) resource.wake()
        return resource
    }

    /** A scope of this run's for one attempt at a stream that restarts, closed when the attempt is given up. */
    fun attempt(): Resources = Resources().also { attempt ->
        attempts += attempt
        if (stopping) attempt.wake()
    }

    /** [attempt] closed and forgotten, so a run that restarts often keeps only the attempt it is on. */
    fun givenUp(attempt: Resources): Throwable? {
        attempts.remove(attempt)
        return attempt.close()
    }

    fun wake() {
        stopping = true
        opened.forEach(Opened::wake)
        attempts.forEach(Resources::wake)
    }

    /** Every resource closed, each whatever the one before it threw; the first throw, if any. */
    // Wide on purpose: one resource that cannot close must not strand the ones after it.
    @Suppress("TooGenericExceptionCaught")
    fun close(): Throwable? {
        val own = opened.fold(null as Throwable?) { first, resource ->
            try {
                resource.close()
                first
            } catch (thrown: Throwable) {
                first ?: thrown
            }
        }
        return attempts.fold(own) { first, attempt -> attempt.close()?.let { first ?: it } ?: first }
    }

    /** Runs [body] with this as the resources of the thread's run. */
    fun <T> around(body: () -> T): T {
        val outer = current.get()
        current.set(this)
        return try {
            body()
        } finally {
            if (outer == null) current.remove() else current.set(outer)
        }
    }

    companion object {
        private val current = ThreadLocal<Resources>()

        /** The resources of the run this thread is pulling, or none off a run's thread. */
        fun here(): Resources? = current.get()
    }
}
