package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Clock
import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.Logger
import io.github.matthewjones372.lark.VirtualThreads
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * lark-stream on lark's own threads: a run is one pull loop on one fork from [on], and nothing else starts.
 * Every stage runs on that fork, when the stage after it asks, so a stage body can block and `bind`.
 */
class Forks(private val on: Executor = VirtualThreads, name: String = "Forks") : StreamBackend {

    /** The name a refusal says a run was started on: a backend built on this one gives its own. */
    @StreamSpi
    override val key: BackendKey = if (name == ForksKey.name) ForksKey else BackendKey(name)

    @StreamSpi
    override fun runs(node: Node): Boolean = node.pulls()

    @StreamSpi
    override fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R> {
        // Read here, on the caller's thread: the fork inherits neither.
        val running = PullRun<E, R>(logger.get(), clock.get())
        on.execute { running.drain(run) }
        return running
    }
}

internal val ForksKey = BackendKey("Forks")

private class PullRun<E, R : Any>(private val log: Logger, private val clock: Clock) : Running<E, R> {

    private val stopped = AtomicBoolean(false)

    override val exit = CompletableFuture<Exit<E, R>>()

    /** The loop ends before its next element, and the exit is `Done` with what the end had by then. */
    override fun stop() = stopped.set(true)

    override fun close() {
        stop()
        exit.join()
    }

    fun drain(run: Run<E, R>) {
        exit.complete(ended(run))
    }

    // The catch is as wide as a pipeline, because everything a stage threw ends the run: a declared
    // failure as `Failed`, and anything else as the `Died` it is logged as.
    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    private fun ended(run: Run<E, R>): Exit<E, R> =
        try {
            // The fused tree is the same for every run of a description, so it is worked out once.
            val pull = run.compiled.getOrCompile(ForksKey) { run.node.optimised() }.pull()
            val elements = generateSequence { if (stopped.get()) null else pull.next() }
            val value = when (val end = run.end) {
                End.Collect -> elements.toList()
                is End.Fold -> elements.fold(end.zero, end.f)
                is End.Native -> error("${end.builder} reached the Forks runner, which start refuses it before")
            }
            Exit.Done(value as R)
        } catch (failure: DeclaredFailure) {
            Exit.Failed(failure.declared())
        } catch (defect: Throwable) {
            log.log(LogLine(LogLevel.Error, defect.oneLine(), clock.now(), defect))
            Exit.Died(defect)
        }
}

/** The line the Pekko backend logs a defect with, so a defect reads the same whichever backend ran it. */
private fun Throwable.oneLine(): String {
    val defect = suppressed.filterIsInstance<Defect>().firstOrNull()
    return if (defect == null) "lark-stream: $this" else "lark-stream: ${defect.message}: $this"
}
