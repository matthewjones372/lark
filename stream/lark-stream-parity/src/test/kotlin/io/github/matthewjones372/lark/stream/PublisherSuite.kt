package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Expectations
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.junit.withClue
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.CompletionStage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Flow
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/** A `Flow.Publisher` read on every backend: what arrives, how much is ever owed, and how a run lets go. */
object PublisherSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("lark-stream-publisher-test")

    private const val SETTLE_SECONDS = 10L

    private val backends: List<Backend> = listOf(OnPekko, OnForks, OnTestStreams, OnActors)

    /** Registers [name] once for each backend, named for it, with that backend made fresh for the test. */
    private fun onEvery(name: String, check: (StreamBackend) -> Expectations) =
        backends.forEach { backend -> test("$name [${backend.name}]") { system -> check(backend.on(system)) } }

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

    init {
        onEvery("a slow reader never has more than prefetch owed to it") { backend ->
            val publisher = Counting(upTo = 10_000)

            val exit = Stream.fromPublisher(publisher, prefetch = 8)
                .map { LockSupport.parkNanos(10_000); it }
                .runFold(0L) { sum, n -> sum + n }
                .run(backend).settled()

            (exit shouldBe Exit.Done((1..10_000L).sum())) and
                withClue("the most ever asked for and not yet sent") { (publisher.mostOwed <= 8) shouldBe true }
        }

        onEvery("a prefetch of one still asks for the next") { backend ->
            Stream.fromPublisher(Counting(upTo = 5), prefetch = 1).runCollect().run(backend).settled() shouldBe
                Exit.Done(listOf(1L, 2L, 3L, 4L, 5L))
        }

        onEvery("a run that stops cancels its subscription") { backend ->
            val publisher = Counting(upTo = Long.MAX_VALUE)
            val reading = Stream.fromPublisher(publisher).runCollect().start(backend)
            publisher.subscribed.await(SETTLE_SECONDS, TimeUnit.SECONDS)

            reading.stop()
            reading.exit.settled()

            publisher.cancelled.await(SETTLE_SECONDS, TimeUnit.SECONDS) shouldBe true
        }

        onEvery("a publisher that fails ends the run with its failure") { backend ->
            val boom = IllegalStateException("boom")
            val failing = Flow.Publisher<Int> { subscriber ->
                subscriber.onSubscribe(Idle)
                subscriber.onError(boom)
            }

            Stream.fromPublisher(failing).runCollect().run(backend).settled() shouldBe Exit.Died(boom)
        }

        onEvery("a publisher that sends more than it was asked for ends the run") { backend ->
            val pushy = Flow.Publisher<Int> { subscriber ->
                subscriber.onSubscribe(Idle)
                (1..3).forEach(subscriber::onNext)
            }

            Stream.fromPublisher(pushy, prefetch = 2).runCollect().run(backend).settled()
                .shouldBeInstanceOf<Exit.Died> { died -> died.cause.shouldBeInstanceOf<IllegalStateException>() }
        }

        test("a publisher is asked for at least one at a time") { _ ->
            shouldThrow<IllegalArgumentException> { Stream.fromPublisher(Counting(upTo = 1), prefetch = 0) }
        }
    }

    /** A subscription that ignores what it is asked: for a publisher that sends regardless. */
    private object Idle : Flow.Subscription {
        override fun request(n: Long) = Unit

        override fun cancel() = Unit
    }

    /**
     * 1 to [upTo], each sent only once asked for, from a thread of its own as most publishers do, recording
     * the most it ever owed.
     */
    private class Counting(private val upTo: Long) : Flow.Publisher<Long> {
        val subscribed = CountDownLatch(1)
        val cancelled = CountDownLatch(1)

        private val owed = AtomicLong()

        @Volatile
        var mostOwed = 0L
            private set

        override fun subscribe(subscriber: Flow.Subscriber<in Long>) {
            val sender = Executors.newSingleThreadExecutor()
            var next = 1L
            subscriber.onSubscribe(
                object : Flow.Subscription {
                    override fun request(n: Long) {
                        mostOwed = maxOf(mostOwed, owed.addAndGet(n))
                        // A request after the end has nothing to start, and one racing the end's shutdown is refused.
                        try {
                            sender.execute(::send)
                        } catch (_: RejectedExecutionException) {
                            // Ended or cancelled: there is nothing left to send.
                        }
                    }

                    // Only ever on the sender's one thread, so `next` needs no lock.
                    private fun send() {
                        while (owed.get() > 0 && next <= upTo && cancelled.count > 0) {
                            owed.decrementAndGet()
                            subscriber.onNext(next++)
                        }
                        if (next > upTo && !sender.isShutdown) {
                            sender.shutdown()
                            subscriber.onComplete()
                        }
                    }

                    override fun cancel() {
                        cancelled.countDown()
                        sender.shutdown()
                    }
                },
            )
            subscribed.countDown()
        }
    }
}
