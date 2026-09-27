package io.github.matthewjones372.lark.app.actor

import io.github.matthewjones372.lark.Deferred
import io.github.matthewjones372.lark.Flock
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.actor.ActorRef
import io.github.matthewjones372.lark.actor.Behaviour
import io.github.matthewjones372.lark.actor.DeadLetter
import io.github.matthewjones372.lark.actor.Failure
import io.github.matthewjones372.lark.actor.ServiceKey
import io.github.matthewjones372.lark.actor.Signal
import io.github.matthewjones372.lark.actor.awaitIdle
import io.github.matthewjones372.lark.actor.find
import io.github.matthewjones372.lark.actor.onDeadLetter
import io.github.matthewjones372.lark.actor.spawn
import io.github.matthewjones372.lark.actor.stop
import io.github.matthewjones372.lark.actor.watch
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.Wiring
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.logDebug
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A flock held open for the application's life, for its actors to live in. It is opened on a thread of its own that
 * waits until the node is released, and releasing it closes the flock: its close hooks run first, and then every
 * actor stops after its running step. Everything here is the flock's own call.
 */
class Actors internal constructor(private val flock: Flock<Nothing>, private val tasks: BlockingQueue<Runnable>) {

    fun <M : Any, S, E> spawn(
        name: String,
        behaviour: Behaviour<M, S, E>,
        capacity: Int = 1024,
        throughput: Int = 64,
        restart: Schedule<Failure<E>, *>? = null,
        stash: Int = 1024,
    ): ActorRef<M> = flock.spawn(name, behaviour, capacity, throughput, restart, stash)

    fun stop(ref: ActorRef<*>): Signal.Terminated = flock.stop(ref).await()

    fun watch(ref: ActorRef<*>): Deferred<Signal.Terminated> = flock.watch(ref)

    fun <M : Any> find(key: ServiceKey<M>): Set<ActorRef<M>> = flock.find(key)

    fun awaitIdle() = flock.awaitIdle()

    /**
     * Runs [block] on the flock's own thread and answers what it returned, or throws what it threw: for what must be
     * started in the flock itself, such as a remote node or a cluster. What it starts closes with the flock, through
     * its close hooks, so a cluster made here leaves before the application's actors stop (spec 0080).
     */
    fun <A> within(block: Flock<Nothing>.() -> A): A {
        check(!closed.get()) { "the actors' flock has closed" }
        val task = FutureTask { flock.block() }
        tasks.put(task)
        return try {
            task.get()
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        }
    }

    // The thread holding the flock open, set as soon as it has started.
    internal lateinit var holder: Thread

    private val closed = AtomicBoolean(false)

    /** Lets the flock close, and waits for it to have: every actor has stopped once this returns. */
    internal fun close() {
        closed.set(true)
        tasks.put(Close)
        holder.join()
    }
}

/** What the flock's thread takes to stop running tasks and let the flock close. */
private val Close = Runnable { }

/**
 * The [Actors] node. [onDeadLetter] is handed every dead letter of its actors; by default each is logged at debug.
 */
fun actors(onDeadLetter: (DeadLetter) -> Unit = { logDebug("dead letter: $it") }): Module = single<Actors> {
    install({ open(onDeadLetter) }) { actors, _ -> actors.close() }
}

/** Opens a flock on a thread of its own, with the clock of the thread that asked, and hands it back once it stands. */
private fun open(onDeadLetter: (DeadLetter) -> Unit): Actors {
    val waits = clock.get()
    val opened = CompletableFuture<Actors>()
    val tasks = LinkedBlockingQueue<Runnable>()
    val holder = Thread.ofVirtual().name("lark-app-actors").start {
        clock.locally(waits) {
            flock<Nothing, Unit> {
                // The guardian stands up here, on the flock's own thread, before any spawn from elsewhere.
                onDeadLetter(onDeadLetter)
                opened.complete(Actors(this, tasks))
                // The flock's thread runs what `within` hands it until the node is released.
                generateSequence { tasks.take() }.takeWhile { it !== Close }.forEach(Runnable::run)
            }
        }
    }
    return opened.join().also { it.holder = holder }
}

/** Spawns [behaviour] on [actors] and stops it when the scope ends, waiting until it has stopped. */
fun <M : Any> Wiring.spawn(actors: Actors, name: String, behaviour: Behaviour<M, *, *>): ActorRef<M> =
    install({ actors.spawn(name, behaviour) }) { ref, _ -> actors.stop(ref) }

/**
 * An actor as a node, keyed by the `ActorRef<M>` of its protocol: two actors are two keys wherever they answer to two
 * protocols, and a node depending on one names the protocol it sends. It has stopped before anything it depends on
 * is released.
 */
inline fun <reified M : Any> actor(name: String, noinline behaviour: () -> Behaviour<M, *, *>): Module =
    single { actors: Actors -> spawn(actors, name, behaviour()) }

inline fun <reified M : Any, reified D1 : Any> actor(
    name: String,
    noinline behaviour: (D1) -> Behaviour<M, *, *>,
): Module = single { actors: Actors, d1: D1 -> spawn(actors, name, behaviour(d1)) }

inline fun <reified M : Any, reified D1 : Any, reified D2 : Any> actor(
    name: String,
    noinline behaviour: (D1, D2) -> Behaviour<M, *, *>,
): Module = single { actors: Actors, d1: D1, d2: D2 -> spawn(actors, name, behaviour(d1, d2)) }
