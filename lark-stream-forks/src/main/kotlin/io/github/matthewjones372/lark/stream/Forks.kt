package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Clock
import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.Logger
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.VirtualThreads
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.logger
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * lark-stream on lark's own threads: a run is one pull loop on one fork from [on]. Every stage runs on that
 * fork, when the stage after it asks, so a stage body can block and `bind`. Only `mapPar` starts more: up
 * to its parallelism of bodies in flight, each on the node's executor, all let go of when the run ends.
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

/**
 * The [Forks] pull loop on a clock a test moves: what lark-stream-test's `TestStreams` is, by its own
 * [name]. It runs what Forks does, and `tick`, `groupedWithin` and `restartOnDefect` besides, which wait
 * on [time] and on nothing else, and `mapPar`, one element at a time in the order they came.
 *
 * A run is workers that take turns, so one stage runs at a time and in the same order every time. `start`
 * returns once the run is over or waiting on a later time, and each move of [time] returns once
 * everything due by then has run, in time order. A stage body that blocks on anything but [time] blocks
 * the test with it.
 */
@StreamSpi
class ForksOnClock(private val time: TestClock, name: String) : StreamBackend {

    @StreamSpi
    override val key: BackendKey = BackendKey(name)

    @StreamSpi
    override fun runs(node: Node): Boolean = node.pullsOnClock()

    @StreamSpi
    override fun <E, R : Any> materialise(run: Run<E, R>): Running<E, R> {
        val turns = Turns(time)
        val waiting = time.register(turns)
        val running = PullRun<E, R>(logger.get(), time, onStop = turns::stop, kept = CopyOnWriteArrayList())
        turns.fork {
            try {
                running.drain(run)
            } finally {
                // Whatever still waits in the run is let go, and the clock no longer stops for it.
                turns.end()
                waiting.close()
            }
        }
        turns.settle()
        return running
    }
}

/** A run that keeps each element that reached its end, so a test can read them before it is over. */
@StreamSpi
interface Emitting {
    fun emitted(): List<Any>
}

private class PullRun<E, R : Any>(
    private val log: Logger,
    private val clock: Clock,
    private val onStop: () -> Unit = {},
    private val kept: MutableList<Any>? = null,
) : Running<E, R>, Emitting {

    private val stopped = AtomicBoolean(false)

    override val exit = CompletableFuture<Exit<E, R>>()

    override fun emitted(): List<Any> = kept?.toList() ?: error("only a run on a test's clock keeps what it emitted")

    /** The loop ends before its next element, and the exit is `Done` with what the end had by then. */
    override fun stop() {
        stopped.set(true)
        onStop()
    }

    override fun close() {
        stop()
        exit.join()
    }

    fun drain(run: Run<E, R>) {
        // What the run holds is let go of before the exit completes: no body or fork outlives its run.
        // The fused tree is the same for every run of a description, so it is worked out once, and so is
        // whether a run of it can start a thread of its own: one that cannot pays nothing for releasing.
        val compiled = run.compiled.getOrCompile(ForksKey) { Compiled(run.node.optimised()) }
        exit.complete(
            if (compiled.forks) Releases.around(Releases()) {
                ended(compiled, run.end)
            } else ended(compiled, run.end),
        )
    }

    // The catch is as wide as a pipeline, because everything a stage threw ends the run: a declared
    // failure as `Failed`, and anything else as the `Died` it is logged as.
    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    private fun ended(compiled: Compiled, end: End): Exit<E, R> =
        try {
            val pull = compiled.tree.pull()
            val pulled = generateSequence { if (stopped.get()) null else pull.next() }
            // Only a run on a test's clock keeps what it emitted: a Forks run pays nothing per element for it.
            val elements = kept?.let { pulled.onEach(it::add) } ?: pulled
            val value = when (end) {
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

/** A description compiled for Forks, once: its fused tree, and whether a run of it can start a thread. */
internal class Compiled(val tree: Node) {
    val forks: Boolean = tree.mayFork()
}

/**
 * Whether pulling this starts a thread of its own, or builds a stream later that might: a `flatMap`'s
 * inner streams and a `catchAll`'s recovery are built only when they are needed.
 */
private fun Node.mayFork(): Boolean =
    this is Node.MapPar || this is Node.Buffer || this is Node.FlatMap || this is Node.CatchAll ||
        children().any { it.mayFork() }
