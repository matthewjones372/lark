package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.Logger
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.TestClock
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.forAll
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeEmpty
import io.github.matthewjones372.lark.junit.shouldBeInstanceOf
import io.github.matthewjones372.lark.junit.shouldContain
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.logger
import io.github.matthewjones372.lark.resource
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** A run on TestStreams is over, or waiting on the test's clock, by the time `start` returns. */
object TestStreamsSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    private data class Odd(val value: Int)

    private val <E, R> Running<E, R>.done: Exit<E, R>?
        get() = exit.toCompletableFuture().takeIf { it.isDone }?.join()

    init {
        test("a run with no time in it is over when run returns, having run one stage at a time") { _ ->
            val threads = ConcurrentLinkedQueue<Thread>()

            val exit = Stream.of(1, 2, 3).map { threads += Thread.currentThread(); it * 2 }.runCollect()
                .run(TestStreams())

            (exit.toCompletableFuture().isDone shouldBe true) and
                (exit.toCompletableFuture().join() shouldBe Exit.Done(listOf(2, 4, 6))) and
                (threads.toSet().size shouldBe 1)
        }

        test("a declared failure ends the run Failed, as on any backend") { _ ->
            val exit = Stream.of(1, 2, 3).mapOrFail { if (it == 2) fail(Odd(it)) else it }.runCollect()
                .run(TestStreams())

            exit.toCompletableFuture().join() shouldBe Exit.Failed(Odd(2))
        }

        test("another backend's own value is refused by its own name") { _ ->
            val elsewhere =
                Stream<Nothing, Int>(Node.Native(Any(), BackendKey("Elsewhere"), "Elsewhere.source", "Here.kt:1"))

            val exit = Stream.of(1, 2).merge(elsewhere).runCollect().run(TestStreams())

            exit.toCompletableFuture().join().shouldBeInstanceOf<Exit.Died> { died ->
                died.cause.message shouldContain "and this run was started on TestStreams"
            }
        }

        test("merge on a test's clock answers in the same order every time, the first stream then the other") { _ ->
            (1..20).forAll { _ ->
                Stream.of(1, 2).merge(Stream.of(3)).runCollect().run(TestStreams()).toCompletableFuture()
                    .join() shouldBe Exit.Done(listOf(1, 2, 3))
            }
        }

        test("mapPar runs one element at a time in the order they came, and its raise is the stream's") { _ ->
            val inFlight = java.util.concurrent.atomic.AtomicInteger()
            val most = java.util.concurrent.atomic.AtomicInteger()

            val running = Stream.from(1..5)
                .mapPar<Odd, Int, Int>(4) { n ->
                    most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    inFlight.decrementAndGet()
                    if (n == 4) fail(Odd(n)) else n * 10
                }
                .runCollect().start(TestStreams())

            (running.done shouldBe Exit.Failed(Odd(4))) and
                (running.emitted() shouldBe listOf(10, 20, 30)) and
                (most.get() shouldBe 1)
        }

        test("an hour of ticks at one a minute is sixty elements, in well under a second") { _ ->
            val clock = TestClock()
            val (checks, took) = measureTimedValue {
                val running = Stream.tick(1.minutes, "t").runCollect().start(TestStreams(clock))

                val none = running.emitted().shouldBeEmpty()
                clock.adjust(1.hours)

                val sixty = running.emitted().size shouldBe 60
                running.close()
                none and sixty and (running.done shouldBe Exit.Done(List(60) { "t" }))
            }
            checks and withClue("took $took") { (took < 1.seconds) shouldBe true }
        }

        test("each move of the clock returns with what fell due by then, and a take ends the run") { _ ->
            val clock = TestClock()
            val running = Stream.tick(10.seconds, 1, after = 5.seconds).scan(0) { total, n -> total + n }.take(4)
                .runCollect().start(TestStreams(clock))

            val first = running.emitted() shouldBe listOf(0)
            clock.adjust(4.seconds)
            val early = running.emitted() shouldBe listOf(0)
            clock.adjust(1.seconds)
            val due = running.emitted() shouldBe listOf(0, 1)
            val open = running.done shouldBe null
            clock.adjust(20.seconds)
            first and early and due and open and (running.done shouldBe Exit.Done(listOf(0, 1, 2, 3)))
        }

        test("a stage body reads the test's clock as lark's clock") { _ ->
            val clock = TestClock(Instant.parse("2026-09-25T12:00:00Z"))
            val running = Stream.tick(1.minutes, Unit).map { io.github.matthewjones372.lark.clock.get().now() }.take(2)
                .runCollect().start(TestStreams(clock))

            clock.adjust(2.minutes)

            running.done shouldBe Exit.Done(
                listOf(Instant.parse("2026-09-25T12:01:00Z"), Instant.parse("2026-09-25T12:02:00Z")),
            )
        }

        test("groupedWithin closes a window when its time is up, and a full group starts the next") { _ ->
            val clock = TestClock()
            val running = Stream.tick(2.seconds, 1).groupedWithin(3, 5.seconds).map { it.size }
                .runCollect().start(TestStreams(clock))

            clock.adjust(5.seconds)
            val closed = withClue("the window closing at 5s held the ticks at 2s and 4s") {
                running.emitted() shouldBe listOf(2)
            }
            clock.adjust(4.seconds)
            val waiting = running.emitted() shouldBe listOf(2)
            clock.adjust(1.seconds)
            val filled =
                withClue("the tick at 10s arrives before the window closing at 10s does, and fills the group") {
                    running.emitted() shouldBe listOf(2, 3)
                }
            clock.adjust(5.seconds)
            val next = withClue("so the next window runs from 10s to 15s, and holds 12s and 14s") {
                running.emitted() shouldBe listOf(2, 3, 2)
            }
            running.close()
            closed and waiting and filled and next
        }

        test("groupedWithin emits what a window held when it closes before the group is full") { _ ->
            val clock = TestClock()
            val running = Stream.tick(2.seconds, 1).take(3).groupedWithin(10, 5.seconds)
                .runCollect().start(TestStreams(clock))

            clock.adjust(5.seconds)
            val held = withClue("the ticks at 2s and 4s, when the window closes at 5s") {
                running.emitted() shouldBe
                    listOf(listOf(1, 1))
            }
            clock.adjust(1.seconds)
            held and withClue("the tick at 6s is the last, and the end emits what the next window held") {
                running.done shouldBe Exit.Done(listOf(listOf(1, 1), listOf(1)))
            }
        }

        test("a restart waits the schedule's delay on the test's clock, and says so at the clock's time") { _ ->
            val clock = TestClock()
            val lines = ConcurrentLinkedQueue<LogLine>()
            val attempts = ConcurrentLinkedQueue<Int>()
            val flaky = logger.locally(Logger { lines += it }) {
                Stream.of(1, 2)
                    .map { n ->
                        attempts += n
                        check(!(n == 2 && attempts.size < 6)) { "not yet" }
                        n
                    }
                    .restartOnDefect(Schedule.spaced(30.seconds))
                    .runCollect()
            }

            val running = flaky.start(TestStreams(clock))

            val first = running.emitted() shouldBe listOf(1)
            clock.adjust(29.seconds)
            val waiting = running.emitted() shouldBe listOf(1)
            clock.adjust(1.seconds)
            val restarted = running.emitted() shouldBe listOf(1, 1)
            clock.adjust(30.seconds)
            first and waiting and restarted and
                (running.done shouldBe Exit.Done(listOf(1, 1, 1, 2))) and
                (
                    lines.map { it.level to it.at } shouldBe listOf(
                        LogLevel.Warn to Instant.EPOCH,
                        LogLevel.Warn to Instant.EPOCH.plusSeconds(30),
                    )
                    )
        }

        test("stopping a run that waits on the clock ends it Done with what it had") { _ ->
            val clock = TestClock()
            val running = Stream.tick(1.seconds, "t").runCollect().start(TestStreams(clock))
            clock.adjust(2.seconds)

            running.stop()

            val stopped = running.done shouldBe Exit.Done(listOf("t", "t"))
            clock.adjust(10.seconds)
            stopped and (running.emitted() shouldBe listOf("t", "t"))
        }

        test("a clock no run waits on moves as it always has") { _ ->
            val clock = TestClock()

            Stream.of(1).runCollect().run(TestStreams(clock))
            clock.adjust(250.milliseconds)

            clock.now() shouldBe Instant.EPOCH.plusMillis(250)
        }
    }
}
