package io.github.matthewjones372.lark.stream

import arrow.core.Either
import arrow.core.nonFatalOrThrow
import arrow.core.raise.either
import io.github.matthewjones372.lark.VirtualThreads
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.Ctx
import io.github.matthewjones372.lark.actor.behaviour
import io.github.matthewjones372.lark.actor.onStart
import io.github.matthewjones372.lark.actor.stay
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One run's operators that Forks forks for, as children of the run's actor. [ctx] is the run actor's, set by each
 * step before it pulls; a node built anywhere but that step (in a feeder's pull, say) is left to Forks, since only the
 * run's own step may spawn its children.
 */
internal class ActorBoundaries(private val batch: Int) : Boundaries {

    @Volatile
    private var stepping: Thread? = null
    private lateinit var ctx: Ctx<*>
    private var spawned = 0

    /** Runs [block] as the run actor's step, whose context spawns this run's boundaries. */
    fun <T> step(ctx: Ctx<*>, block: () -> T): T {
        this.ctx = ctx
        stepping = Thread.currentThread()
        return try {
            block()
        } finally {
            stepping = null
        }
    }

    // A feeder that pulls its upstream binds the run's resources, so it reaches here too; it gets Forks' way.
    internal lateinit var pulling: Pulling<*, *>

    override fun pull(node: Node, pulled: (Node) -> Pull): Pull? {
        if (stepping !== Thread.currentThread()) return null
        return when (node) {
            is Node.MapPar -> if (node.on === VirtualThreads) node.workers(pulled(node.upstream)) else null
            is Node.Buffer -> node.fed(pulled(node.upstream))
            else -> null
        }
    }

    private fun <M : Any, S, E> spawn(name: String, behaviour: Behaviour<M, S, E>): ActorRef<M> =
        ctx.spawn("$name-${++spawned}", behaviour, null)

    /**
     * `mapPar(n)` on [Node.MapPar.parallelism] worker actors, each told its elements in turn: at most `n` bodies are
     * in flight, and their answers come back in the order the elements came.
     */
    private fun Node.MapPar.workers(up: Pull): Pull {
        val body = guarded("mapPar", at) { a: Any -> either { f(a) } }
        val workers = List(parallelism) { spawn("mapPar", worker(body)) }
        val window = ArrayDeque<CompletableFuture<Either<Any?, Any>>>()
        var drained = false
        var told = 0
        return Pull {
            while (!drained && window.size < parallelism) {
                val a = up.next()
                if (a == null) {
                    drained = true
                } else {
                    val answer = CompletableFuture<Either<Any?, Any>>()
                    workers[told++ % parallelism].tell(Work(a, answer))
                    window.addLast(answer)
                }
            }
            window.removeFirstOrNull()?.let { head ->
                val answer = try {
                    head.get()
                } catch (failed: ExecutionException) {
                    throw failed.cause ?: failed
                }
                answer.fold({ e -> throw DeclaredFailure(e) }, { b -> b })
            }
        }
    }

    /** `buffer(n)`: a feeder actor pulls upstream ahead of the reader into a queue, while it has room for `n`. */
    private fun Node.Buffer.fed(up: Pull): Pull {
        val feeder = Feeder(up, size, batch, pulling)
        spawn("buffer", feeder.behaviour())
        var ended = false
        return Pull {
            if (ended) {
                null
            } else {
                when (val next = feeder.queue.take()) {
                    Fed.Done -> null.also { ended = true }
                    is Fed.Threw -> throw next.thrown.also { ended = true }
                    else -> next.also { feeder.took() }
                }
            }
        }
    }
}

/** One element for a `mapPar` worker, and where its answer goes. */
private class Work(val element: Any, val answer: CompletableFuture<Either<Any?, Any>>)

// Whatever a body threw is its answer, which the reader throws again in order; only a fatal throw is not.
@Suppress("TooGenericExceptionCaught")
private fun worker(body: (Any) -> Either<Any?, Any>): Behaviour<Work, Unit, Nothing> =
    behaviour<Work, Unit>(Unit) { _, _, work ->
        try {
            work.answer.complete(body(work.element))
        } catch (thrown: Throwable) {
            work.answer.completeExceptionally(thrown.nonFatalOrThrow())
        }
        stay()
    }

/** The end of what a feeder pulled, behind every element before it. */
private sealed interface Fed {
    data object Done : Fed

    class Threw(val thrown: Throwable) : Fed
}

/** What a feeder tells itself: that it has room to pull into. */
private data object Room

/**
 * Pulls [up] into [queue] while there is room for [size] elements, [batch] at a time so that it shares its runner,
 * and stalls when the room runs out until the reader takes one. Of the feeder and the reader, whichever sees the other
 * last wakes it: the feeder stalls before it looks for room again, and the reader makes room before it looks for a
 * stall.
 */
private class Feeder(private val up: Pull, size: Int, private val batch: Int, private val run: Pulling<*, *>) {
    val queue = LinkedBlockingQueue<Any>()
    private val room = AtomicInteger(size)
    private val stalled = AtomicBoolean(false)
    private var ended = false

    // Its own ref, taken from its first step: the reader wakes it only after taking an element that step put.
    @Volatile
    private lateinit var ref: ActorRef<Room>

    fun behaviour(): Behaviour<Room, Unit, Nothing> =
        behaviour<Room, Unit>(Unit) { ctx, _, _ ->
            ref = ctx.self
            run.within(::feed)
            stay()
        }.onStart { ctx -> ctx.self.tell(Room) }

    /** The reader took an element: there is room for one more, and a stalled feeder goes on. */
    fun took() {
        room.incrementAndGet()
        if (stalled.get() && stalled.compareAndSet(true, false)) ref.tell(Room)
    }

    // Whatever upstream threw ends what the reader sees, after every element before it.
    @Suppress("TooGenericExceptionCaught")
    private fun feed() {
        var pulled = 0
        while (!ended && room.get() > 0 && pulled < batch) {
            val a = try {
                up.next()
            } catch (thrown: Throwable) {
                queue.put(Fed.Threw(thrown.nonFatalOrThrow()))
                ended = true
                return
            }
            if (a == null) {
                queue.put(Fed.Done)
                ended = true
            } else {
                room.decrementAndGet()
                queue.put(a)
                pulled++
            }
        }
        when {
            ended -> Unit

            room.get() > 0 -> ref.tell(Room)

            else -> {
                stalled.set(true)
                if (room.get() > 0 && stalled.compareAndSet(true, false)) ref.tell(Room)
            }
        }
    }
}
