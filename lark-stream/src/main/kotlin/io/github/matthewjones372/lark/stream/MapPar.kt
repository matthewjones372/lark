package io.github.matthewjones372.lark.stream

import arrow.core.Either
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

/**
 * As [mapPar], on a stream that has yet to name a failure: this one reads the failure out of the body.
 *
 * It has a name of its own, `mapOrFail`'s suffix, because Kotlin fixes the type variable before it
 * looks at the body. Under one name, the form that reads `F` from a `raise` wins on a
 * `Stream<Nothing, A>`, and a body that never raises leaves it nothing to read.
 */
// The two erase to one JVM signature, so one of them needs a name of its own down there. Kotlin
// callers never see it.
@JvmName("mapParOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, A>.mapParOrFail(parallelism: Int, f: Raise<F>.(A) -> B): Stream<F, B> =
    via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
@JvmName("mapParOrFailDeclaringOn")
fun <F, A : Any, B : Any> Stream<Nothing, A>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Raise<F>.(A) -> B,
): Stream<F, B> = via(Pipe.mapPar(parallelism, on, f))

/** As above, for a stream whose failure type is already named: [mapPar] under the name that says it may fail. */
fun <E, A : Any, B : Any> Stream<E, A>.mapParOrFail(parallelism: Int, f: Raise<E>.(A) -> B): Stream<E, B> =
    mapPar(parallelism, f)

/** The same, with every body run on [on]. */
fun <E, A : Any, B : Any> Stream<E, A>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Raise<E>.(A) -> B,
): Stream<E, B> = mapPar(parallelism, on, f)

/** As the stream pair above, on a pipe that has yet to name a failure. */
@JvmName("mapParOrFailPipeDeclaring")
fun <F, In, Out : Any, Out2 : Any> Pipe<Nothing, In, Out>.mapParOrFail(
    parallelism: Int,
    f: Raise<F>.(Out) -> Out2,
): Pipe<F, In, Out2> = via(Pipe.mapPar(parallelism, VirtualThreads, f))

/** The same, with every body run on [on]. */
@JvmName("mapParOrFailPipeDeclaringOn")
fun <F, In, Out : Any, Out2 : Any> Pipe<Nothing, In, Out>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Raise<F>.(Out) -> Out2,
): Pipe<F, In, Out2> = via(Pipe.mapPar(parallelism, on, f))

fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapParOrFail(
    parallelism: Int,
    f: Raise<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = mapPar(parallelism, f)

/** The same, with every body run on [on]. */
fun <E, In, Out : Any, Out2 : Any> Pipe<E, In, Out>.mapParOrFail(
    parallelism: Int,
    on: Executor,
    f: Raise<E>.(Out) -> Out2,
): Pipe<E, In, Out2> = mapPar(parallelism, on, f)

private fun <E, A : Any, B : Any> forked(parallelism: Int, on: Executor, f: Raise<E>.(A) -> B): Pipe<E, A, B> {
    // The raise is folded into an Either inside the guard, so that the guard is the one place
    // deciding what a defect says: a declared failure comes back as a `Left` the stage is failed
    // with, and everything else picks up the three facts on its way out.
    val body = guarded("mapPar", buildSite()) { a: A -> either { f(a) } }
    return Pipe(
        // What is in flight belongs to the run rather than to the description, which can be
        // materialised again while an earlier run of it is still going.
        Flow.fromMaterializer<A, B, NotUsed> { _, _ ->
            val bodies = Bodies(on)
            // Pekko's own mapAsync rather than this library's: a body answers with a `B : Any`, so
            // there is no null completion to guard, and `mapPar` is the operator a defect names.
            Flow.create<A>().mapAsync(parallelism) { a -> bodies.start { body(a) } }
                .watchTermination { mat, ended ->
                    // Pekko never cancels the stage a mapAsync is waiting on, so this is where a body
                    // learns that the stream which asked for its element has gone.
                    ended.whenComplete { _, _ -> bodies.tearDown() }
                    mat
                }
        }.mapMaterializedValue { NotUsed.getInstance() },
    )
}

/** The bodies one run has in flight: at most `mapAsync`'s parallelism of them, each dropped as it ends. */
private class Bodies(private val on: Executor) {

    private val running = ConcurrentHashMap.newKeySet<Body>()

    fun <E, B : Any> start(body: () -> Either<E, B>): CompletionStage<B> {
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
 * The one place a body's answer becomes a stage's: a `Left` is the declared failure `run` unwraps, and
 * anything thrown is the defect it answers `Died` with, described by the guard already. Throwable is
 * caught because a body that ended its thread instead would leave `mapAsync` waiting on a stage nobody
 * will ever complete.
 */
private fun <E, B : Any> CompletableFuture<B>.completeWith(body: () -> Either<E, B>) {
    try {
        body().fold({ e -> completeExceptionally(DeclaredFailure(e)) }, { b -> complete(b) })
    } catch (t: Throwable) {
        completeExceptionally(t)
    }
}
