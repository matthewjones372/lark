package io.github.matthewjones372.lark.stream

import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.matthewjones372.lark.VirtualThreads
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Flow
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Up to [parallelism] element bodies run at once, each on a virtual thread of its own and in a
 * `Raise<E>`; the results keep the input's order, as [mapAsync]'s do.
 */
fun <E, A : Any, B : Any> Stream<E, A>.mapPar(parallelism: Int, f: Raise<E>.(A) -> B): Stream<E, B> =
    via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
fun <E, A : Any, B : Any> Stream<E, A>.mapPar(parallelism: Int, on: Executor, f: Raise<E>.(A) -> B): Stream<E, B> =
    via(Pipe.mapPar(parallelism, on, f))

/**
 * As above, on a stream that has yet to name a failure: this one reads the failure out of the body.
 *
 * The pair is `mapOrFail`'s, for its reason: Kotlin fixes the failure type from the receiver before it
 * looks at the `raise` naming one, so on a `Stream<Nothing, A>` a single signature would pin it to
 * `Nothing`. The more specific receiver decides, so a call site never picks between them.
 */
// The two erase to one JVM signature, so one of them needs a name of its own down there. Kotlin
// callers never see it.
@JvmName("mapParDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, A>.mapPar(parallelism: Int, f: Raise<F>.(A) -> B): Stream<F, B> =
    via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
@JvmName("mapParDeclaringOn")
fun <F, A : Any, B : Any> Stream<Nothing, A>.mapPar(
    parallelism: Int,
    on: Executor,
    f: Raise<F>.(A) -> B,
): Stream<F, B> = via(Pipe.mapPar(parallelism, on, f))

/** The starting form: a pipe of bodies, each on a virtual thread of its own. */
fun <E, A : Any, B : Any> Pipe.Companion.mapPar(parallelism: Int, f: Raise<E>.(A) -> B): Pipe<E, A, B> =
    forked(parallelism, VirtualThreads, f)

/** The same, with every body run on [on]. */
fun <E, A : Any, B : Any> Pipe.Companion.mapPar(
    parallelism: Int,
    on: Executor,
    f: Raise<E>.(A) -> B,
): Pipe<E, A, B> = forked(parallelism, on, f)

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapPar(
    parallelism: Int,
    f: Raise<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapPar(
    parallelism: Int,
    on: Executor,
    f: Raise<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = via(Pipe.mapPar(parallelism, on, f))

/** As the stream pair above, on a pipe that has yet to name a failure. */
@JvmName("mapParPipeDeclaring")
fun <F, In, Out : Any, Out2 : Any> Pipe<Nothing, In, Out>.mapPar(
    parallelism: Int,
    f: Raise<F>.(Out) -> Out2,
): Pipe<F, In, Out2> = via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
@JvmName("mapParPipeDeclaringOn")
fun <F, In, Out : Any, Out2 : Any> Pipe<Nothing, In, Out>.mapPar(
    parallelism: Int,
    on: Executor,
    f: Raise<F>.(Out) -> Out2,
): Pipe<F, In, Out2> = via(Pipe.mapPar(parallelism, on, f))

private fun <E, A : Any, B : Any> forked(parallelism: Int, on: Executor, f: Raise<E>.(A) -> B): Pipe<E, A, B> =
    Pipe(
        // What is in flight belongs to the run rather than to the description, which can be
        // materialised again while an earlier run of it is still going.
        Flow.fromMaterializer<A, B, NotUsed> { _, _ ->
            val bodies = Bodies(on)
            Pipe.mapAsync<A, B>(parallelism) { a -> bodies.start<E, B> { f(a) } }.flow
                .watchTermination { mat, ended ->
                    // Pekko never cancels the stage a mapAsync is waiting on, so this is where a body
                    // learns that the stream which asked for its element has gone.
                    ended.whenComplete { _, _ -> bodies.tearDown() }
                    mat
                }
        }.mapMaterializedValue { NotUsed.getInstance() },
    )

/** The bodies one run has in flight: at most `mapAsync`'s parallelism of them, each dropped as it ends. */
private class Bodies(private val on: Executor) {

    private val running = ConcurrentHashMap.newKeySet<Body>()

    fun <E, B : Any> start(body: Raise<E>.() -> B): CompletionStage<B> {
        val stage = CompletableFuture<B>()
        val lent = Body(stage)
        running.add(lent)
        on.execute {
            lent.borrow()
            try {
                stage.completeWith(body)
            } finally {
                running.remove(lent)
                lent.giveBack()
            }
        }
        return stage
    }

    fun tearDown() = running.forEach { it.cancel() }
}

/**
 * The thread a body was lent and the cancel aimed at it, moving under one lock as lark's own forks do:
 * read outside it, an interrupt could land on a thread the body had already given back, and so on
 * whatever the executor ran next. Both are `Atomic` because no file in this library holds a `var`.
 */
private class Body(private val stage: CompletableFuture<*>) {

    private val cancelling = ReentrantLock()
    private val borrowed = AtomicReference<Thread?>()
    private val cancelled = AtomicBoolean(false)

    fun borrow() = cancelling.withLock {
        borrowed.set(Thread.currentThread())
        // Cancelled before its turn on the executor came: the body starts interrupted rather than
        // running on as though the stream that asked for its element were still there.
        if (cancelled.get()) Thread.currentThread().interrupt()
    }

    fun giveBack() {
        cancelling.withLock {
            borrowed.set(null)
            // The next task on a borrowed thread is not this body's to interrupt.
            Thread.interrupted()
        }
    }

    fun cancel() {
        // A value arriving after this is dropped, as it is for a fork nobody is left to read.
        stage.cancel(false)
        cancelling.withLock {
            cancelled.set(true)
            borrowed.get()?.interrupt()
        }
    }
}

/**
 * The one place a body's answer becomes a stage's: a raise is the declared failure `run` unwraps, and
 * anything thrown is the defect it answers `Died` with. Throwable is caught because a body that ended
 * its thread instead would leave `mapAsync` waiting on a stage nobody will ever complete.
 */
private fun <E, B : Any> CompletableFuture<B>.completeWith(body: Raise<E>.() -> B) {
    try {
        either { body() }.fold({ e -> completeExceptionally(DeclaredFailure(e)) }, { b -> complete(b) })
    } catch (t: Throwable) {
        completeExceptionally(t)
    }
}
