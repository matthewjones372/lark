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
 * fork, when the stage after it asks, so a stage body can block and `bind`. Only `mapPar` and `buffer`
 * start more, from [on] as well (a `mapPar` given an executor of its own keeps it), and the run lets go
 * of every one of them before its exit completes.
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
        val running = PullRun<E, R>(logger.get(), clock.get(), on)
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
        val running = PullRun<E, R>(
            logger.get(),
            time,
            VirtualThreads,
            onStop = turns::stop,
            kept = CopyOnWriteArrayList(),
            interrupting = false,
        )
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
    private val on: Executor,
    private val onStop: () -> Unit = {},
    private val kept: MutableList<Any>? = null,
    /** Whether a stop interrupts the loop: on Forks yes, on a test's clock the workers are parked instead. */
    private val interrupting: Boolean = true,
) : Running<E, R>, Emitting {

    private val stopped = AtomicBoolean(false)

    /** The thread running the loop, while it runs it: a stop interrupts it there and nowhere else. */
    private var loop: Thread? = null

    override val exit = CompletableFuture<Exit<E, R>>()

    override fun emitted(): List<Any> = kept?.toList() ?: error("only a run on a test's clock keeps what it emitted")

    /**
     * Ends the run now: the loop stops before its next element, and whatever it is waiting on (a body in
     * flight, an empty buffer, a stage that blocks) is interrupted. The exit is `Done` with what the end
     * had by then.
     */
    override fun stop() {
        stopped.set(true)
        if (interrupting) synchronized(this) { loop?.interrupt() }
        onStop()
    }

    override fun close() {
        stop()
        exit.join()
    }

    // The catch is as wide as the run: an exit that never completes is a caller waiting for ever.
    @Suppress("TooGenericExceptionCaught")
    fun drain(run: Run<E, R>) {
        synchronized(this) { loop = Thread.currentThread() }
        val ended = try {
            // What the run holds is let go of before the exit completes: no body or fork outlives its run.
            // The fused tree is the same for every run of a description, so it is worked out once, and so
            // is whether a run of it can start a thread of its own: one that cannot pays nothing for it.
            val compiled = run.compiled.getOrCompile(ForksKey) { Compiled(run.node.optimised()) }
            if (compiled.forks) {
                Releases.around(Releases(on)) { ended(compiled, run.end) }
            } else {
                ended(compiled, run.end)
            }
        } catch (unreleased: Throwable) {
            log.log(LogLine(LogLevel.Error, unreleased.oneLine(), clock.now(), unreleased))
            Exit.Died(unreleased)
        } finally {
            synchronized(this) {
                loop = null
                // A stop that came after the loop's last wait leaves the flag set; the thread may be pooled.
                Thread.interrupted()
            }
        }
        exit.complete(ended)
    }

    // The catch is as wide as a pipeline, because everything a stage threw ends the run: a declared
    // failure as `Failed`, an interruption a stop caused as `Done`, and anything else as the `Died` it
    // is logged as.
    @Suppress("TooGenericExceptionCaught", "UNCHECKED_CAST")
    private fun ended(compiled: Compiled, end: End): Exit<E, R> {
        val sink = sinkFor(end)
        return try {
            val pull = compiled.tree.pull()
            while (!stopped.get()) {
                val a = pull.next() ?: break
                kept?.add(a)
                sink.add(a)
            }
            Exit.Done(sink.value() as R)
        } catch (failure: DeclaredFailure) {
            Exit.Failed(failure.declared())
        } catch (defect: Throwable) {
            if (stopped.get() && defect.isInterruption()) {
                Exit.Done(sink.value() as R)
            } else {
                log.log(LogLine(LogLevel.Error, defect.oneLine(), clock.now(), defect))
                Exit.Died(defect)
            }
        }
    }
}

/** What the end of a run has so far, kept as it goes so that a stop keeps it too. */
private sealed interface Sink {
    fun add(a: Any)

    fun value(): Any

    class Collecting : Sink {
        private val collected = ArrayList<Any>()

        override fun add(a: Any) {
            collected.add(a)
        }

        override fun value(): Any = collected
    }

    class Folding(private val fold: End.Fold) : Sink {
        private var folded: Any = fold.zero

        override fun add(a: Any) {
            folded = fold.f(folded, a)
        }

        override fun value(): Any = folded
    }
}

private fun sinkFor(end: End): Sink =
    when (end) {
        End.Collect -> Sink.Collecting()
        is End.Fold -> Sink.Folding(end)
        is End.Native -> error("${end.builder} reached the Forks runner, which start refuses it before")
    }

/** An interruption, or a failure it caused: what a stop leaves behind in whatever it woke. */
private fun Throwable.isInterruption(): Boolean =
    generateSequence(this) { it.cause }.take(CAUSES_READ).any { it is InterruptedException }

/** How deep a cause chain is read for an interruption: far enough for a wrapper or two, and no cycle. */
private const val CAUSES_READ = 8

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
    this is Node.MapPar || this is Node.Buffer || this is Node.Merge || this is Node.FlatMap || this is Node.CatchAll ||
        children().any { it.mayFork() }
