package io.github.matthewjones372.lark

import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Every decision a schedule makes for one input, so no test has to time a delay to know it was decided. */
private fun <A, B> Schedule<A, B>.decisionsFor(input: A, limit: Int): List<Schedule.Decision<A, B>> {
    val decisions = mutableListOf<Schedule.Decision<A, B>>()
    var next = step
    while (decisions.size < limit) {
        val decision = next(input)
        decisions += decision
        when (decision) {
            is Schedule.Decision.Continue -> next = decision.step
            is Schedule.Decision.Done -> break
        }
    }
    return decisions
}

private fun <A, B> Schedule<A, B>.delaysFor(input: A, limit: Int): List<Duration> =
    decisionsFor(input, limit).filterIsInstance<Schedule.Decision.Continue<A, B>>().map { it.delay }

private fun <A, B> Schedule<A, B>.outputsFor(input: A, limit: Int): List<B> =
    decisionsFor(input, limit).map { it.output }

class ScheduleTest {

    @Test
    fun `recurs retries exactly n times and then rethrows the last failure`() {
        val boom = Boom()
        val attempts = AtomicInteger(0)

        val thrown = shouldThrow<Boom> {
            Schedule.recurs<Throwable>(3).retry {
                attempts.incrementAndGet()
                throw boom
            }
        }

        thrown shouldBeSameInstanceAs boom
        withClue("the first attempt is not a retry, so three retries is four calls") {
            attempts.get() shouldBe 4
        }
    }

    @Test
    fun `retry returns as soon as the action stops throwing`() {
        val attempts = AtomicInteger(0)

        val answer = Schedule.recurs<Throwable>(5).retry {
            if (attempts.incrementAndGet() < 3) throw Boom() else "answered"
        }

        answer shouldBe "answered"
        attempts.get() shouldBe 3
    }

    @Test
    fun `retryOrElse answers from the last failure and the schedule's last output`() {
        val boom = Boom()

        val answer = Schedule.recurs<Throwable>(2).retryOrElse({ throw boom }) { failure, count ->
            "gave up after $count on ${failure.message}"
        }

        answer shouldBe "gave up after 2 on boom"
    }

    @Test
    fun `retryRaise re-raises the last raise once the schedule is done`() {
        val attempts = AtomicInteger(0)

        val raised = Schedule.recurs<Bad>(2).retryRaise {
            raise(Bad("attempt ${attempts.incrementAndGet()}"))
        }

        raised shouldBe Bad("attempt 3").left()
        attempts.get() shouldBe 3
    }

    @Test
    fun `retryRaise returns the first success, and retry re-raises into the enclosing scope`() {
        val attempts = AtomicInteger(0)

        Schedule.recurs<Bad>(5).retryRaise {
            if (attempts.incrementAndGet() < 2) raise(Bad("again")) else "answered"
        } shouldBe "answered".right()

        either<Bad, Int> {
            retry(Schedule.recurs(1)) { raise(Bad("enclosing")) }
        } shouldBe Bad("enclosing").left()
    }

    @Test
    fun `repeat answers with the schedule's output`() {
        val runs = AtomicInteger(0)

        val output = Schedule.recurs<Int>(3).repeat { runs.incrementAndGet() }

        output shouldBe 3L
        withClue("the action runs once more to produce the input the schedule is done on") {
            runs.get() shouldBe 4
        }

        val counted = AtomicInteger(0)
        Schedule.collect<Int>()
            .doUntil { input, _ -> input == 3 }
            .repeat { counted.incrementAndGet() } shouldContainExactly listOf(1, 2, 3)
    }

    @Test
    fun `exponential doubles its delay, and the other backoffs have their own shapes`() {
        Schedule.exponential<Unit>(100.milliseconds).delaysFor(Unit, 4) shouldContainExactly
            listOf(100.milliseconds, 200.milliseconds, 400.milliseconds, 800.milliseconds)

        Schedule.exponential<Unit>(100.milliseconds, factor = 3.0).delaysFor(Unit, 3) shouldContainExactly
            listOf(100.milliseconds, 300.milliseconds, 900.milliseconds)

        Schedule.linear<Unit>(100.milliseconds).delaysFor(Unit, 3) shouldContainExactly
            listOf(100.milliseconds, 200.milliseconds, 300.milliseconds)

        Schedule.fibonacci<Unit>(100.milliseconds).delaysFor(Unit, 5) shouldContainExactly
            listOf(100.milliseconds, 100.milliseconds, 200.milliseconds, 300.milliseconds, 500.milliseconds)

        Schedule.spaced<Unit>(100.milliseconds).delaysFor(Unit, 3) shouldContainExactly
            listOf(100.milliseconds, 100.milliseconds, 100.milliseconds)

        Schedule.forever<Unit>().outputsFor(Unit, 3) shouldContainExactly listOf(0L, 1L, 2L)
        Schedule.identity<String>().outputsFor("in", 2) shouldContainExactly listOf("in", "in")
    }

    @Test
    fun `recurs is done at the nth decision, and collect gathers what came before`() {
        Schedule.recurs<Unit>(2).outputsFor(Unit, 5) shouldContainExactly listOf(0L, 1L, 2L)

        val decisions = Schedule.recurs<Unit>(2).decisionsFor(Unit, 5)
        decisions.last().shouldBeInstanceOf<Schedule.Decision.Done<Long>>()

        Schedule.collect<String>().outputsFor("x", 3) shouldContainExactly
            listOf(listOf("x"), listOf("x", "x"), listOf("x", "x", "x"))
    }

    @Test
    fun `doWhile stops at the first input it does not hold of, and doUntil is its inverse`() {
        Schedule.doWhile<Int> { input, _ -> input < 10 }.outputsFor(3, 4) shouldContainExactly
            listOf(3, 3, 3, 3)

        Schedule.doWhile<Int> { input, _ -> input < 10 }.decisionsFor(30, 4).single()
            .shouldBeInstanceOf<Schedule.Decision.Done<Int>>()

        Schedule.doUntil<Int> { input, _ -> input < 10 }.decisionsFor(3, 4).single()
            .shouldBeInstanceOf<Schedule.Decision.Done<Int>>()
    }

    @Test
    fun `and continues while both do, taking the longer delay`() {
        val paired = Schedule.spaced<Unit>(50.milliseconds) and Schedule.recurs(2)

        paired.outputsFor(Unit, 5) shouldContainExactly listOf(0L to 0L, 1L to 1L, 2L to 2L)
        withClue("the longer of the two delays is the one waited") {
            paired.delaysFor(Unit, 5) shouldContainExactly listOf(50.milliseconds, 50.milliseconds)
        }

        val summed = Schedule.spaced<Unit>(50.milliseconds)
            .and(Schedule.spaced(30.milliseconds), { a, b -> a + b }) { left, right -> left + right }
        summed.delaysFor(Unit, 2) shouldContainExactly listOf(80.milliseconds, 80.milliseconds)
        summed.outputsFor(Unit, 2) shouldContainExactly listOf(0L, 2L)
    }

    @Test
    fun `or continues while either does, with the stopped side's output null`() {
        val either = Schedule.recurs<Unit>(1).or(
            Schedule.spaced(50.milliseconds),
            { left, right -> "$left/$right" },
        ) { left, right -> maxOf(left ?: Duration.ZERO, right ?: Duration.ZERO) }

        either.outputsFor(Unit, 3) shouldContainExactly listOf("0/0", "null/1", "null/2")
    }

    @Test
    fun `andThen runs the first schedule out and then the second`() {
        val chained = Schedule.recurs<Unit>(1) andThen Schedule.recurs(1)

        withClue("the first schedule's own last output is a Left, and the second starts after it") {
            chained.outputsFor(Unit, 5) shouldContainExactly
                listOf(0L.left(), 1L.left(), 0L.right(), 1L.right())
        }

        val merged = Schedule.recurs<Unit>(1).andThen(Schedule.recurs(1), { "first $it" }) { "second $it" }
        merged.outputsFor(Unit, 5) shouldContainExactly
            listOf("first 0", "first 1", "second 0", "second 1")
    }

    @Test
    fun `zipLeft and zipRight keep one side's output and map transforms it`() {
        val left = Schedule.recurs<Unit>(2) zipLeft Schedule.spaced(10.milliseconds)
        left.outputsFor(Unit, 4) shouldContainExactly listOf(0L, 1L, 2L)

        val right = Schedule.recurs<Unit>(2) zipRight Schedule.spaced(10.milliseconds)
        right.outputsFor(Unit, 4) shouldContainExactly listOf(0L, 1L, 2L)

        Schedule.recurs<Unit>(1).map { "run $it" }.outputsFor(Unit, 4) shouldContainExactly
            listOf("run 0", "run 1")

        Schedule.spaced<Unit>(40.milliseconds).delayed { _, delay -> delay / 2 }.delaysFor(Unit, 2) shouldContainExactly
            listOf(20.milliseconds, 20.milliseconds)
    }

    @Test
    fun `jittered stays between the bounds it was given`() {
        val jittered = Schedule.spaced<Unit>(100.milliseconds).jittered(0.5, 1.0, Random(42))

        val delays = jittered.delaysFor(Unit, 20)

        delays.size shouldBe 20
        withClue("a seeded Random makes this a claim about the bounds, not about luck: $delays") {
            delays.all { it in 50.milliseconds..100.milliseconds } shouldBe true
        }
        withClue("a jitter that never moved the delay would not be one") {
            delays.distinct().size shouldBe delays.size
        }
    }

    @Test
    fun `an interrupt during a schedule's sleep ends it with the InterruptedException`() {
        val started = CountDownLatch(1)
        val interrupted = AtomicBoolean(false)

        flock<Bad, String> {
            async {
                try {
                    Schedule.spaced<Throwable>(1.seconds).retry {
                        started.countDown()
                        throw Boom()
                    }
                } catch (stop: InterruptedException) {
                    interrupted.set(true)
                    "interrupted"
                }
            }
            started.await()
            "returned"
        } shouldBe "returned".right()

        withClue("the delay is waited on the calling thread, so an interrupt lands in it") {
            interrupted.get() shouldBe true
        }
    }
}
