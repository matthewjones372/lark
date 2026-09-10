package io.github.matthewjones372.lark.app

import arrow.core.Either
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

// A release that has not finished by then is one the process stops waiting for: a shutdown hook that
// never returns is a container that has to be killed rather than stopped.
private const val TEARDOWN_LIMIT_SECONDS = 30L

/** The process being asked to stop, as a value, so a test can ask without a signal. */
class Shutdown {

    private val requested = CountDownLatch(1)

    fun request() {
        requested.countDown()
    }

    internal fun await() {
        requested.await()
    }
}

/** What a running application is handed. */
interface AppScope {

    /** Returns when the process is asked to stop. */
    fun awaitShutdown()
}

/** Starts the graph, runs [block] against the node it asks for, and releases everything after. */
inline fun <reified A : Any> Module.application(
    shutdown: Shutdown,
    noinline block: AppScope.(A) -> Unit,
): Either<StartupError, Unit> = use<A, Unit> { root -> appScope(shutdown).block(root) }

@PublishedApi
internal fun appScope(shutdown: Shutdown): AppScope = object : AppScope {
    override fun awaitShutdown() = shutdown.await()
}

/** What went wrong, in the words the reader needs to fix it. */
fun StartupError.describe(): String = when (this) {
    is StartupError.Unwireable -> errors.report()
    is StartupError.Refused -> "lark-app: ${labelOf(key)} refused to start: $reason"
    is StartupError.NoSuchNode -> "lark-app: nothing in the graph builds ${labelOf(key)}"
}

/** Zero only when the application left of its own accord. */
fun Either<StartupError, Unit>.exitCode(): Int = fold({ 1 }, { 0 })

/**
 * The whole of a `main`: a signal stops the application, its releases run before the process leaves,
 * and what went wrong is on stderr.
 */
inline fun <reified A : Any> runApp(module: Module, noinline block: AppScope.(A) -> Unit): Nothing =
    leaving { shutdown -> module.application(shutdown, block) }

@PublishedApi
internal fun leaving(run: (Shutdown) -> Either<StartupError, Unit>): Nothing {
    val shutdown = Shutdown()
    val torndown = CountDownLatch(1)
    val hook = Thread {
        shutdown.request()
        torndown.await(TEARDOWN_LIMIT_SECONDS, TimeUnit.SECONDS)
    }

    Runtime.getRuntime().addShutdownHook(hook)
    val outcome = run(shutdown)
    torndown.countDown()
    // Removing it during a shutdown it is already running in is the illegal state, and by then the
    // process is leaving anyway.
    runCatching { Runtime.getRuntime().removeShutdownHook(hook) }

    outcome.leftOrNull()?.let { System.err.println(it.describe()) }
    exitProcess(outcome.exitCode())
}
