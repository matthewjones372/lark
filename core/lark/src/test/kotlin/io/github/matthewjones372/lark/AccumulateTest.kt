package io.github.matthewjones372.lark

import arrow.core.NonEmptyList
import arrow.core.left
import arrow.core.nonEmptyListOf
import arrow.core.raise.either
import arrow.core.right
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

// Long enough that an interrupt sent by a sibling's raise would have landed inside the pause below.
private const val SETTLE_MILLIS = 250L

/** A branch that keeps running after a sibling has raised, and says whether anything interrupted it. */
private class Bystander {
    private val interrupted = AtomicBoolean(false)

    fun waitOutARaise(raised: CountDownLatch): String =
        try {
            raised.await()
            Thread.sleep(SETTLE_MILLIS)
            "completed"
        } catch (stop: InterruptedException) {
            interrupted.set(true)
            "interrupted"
        }

    fun wasInterrupted(): Boolean = interrupted.get()
}

class AccumulateTest {

    @Test
    fun `two raising branches answer with a NonEmptyList of both, in branch order`() {
        val accumulated = either<NonEmptyList<Bad>, Int> {
            parZipOrAccumulate(
                { raise(Bad("first")) },
                { raise(Bad("second")) },
            ) { _: Int, _: Int -> 0 }
        }

        withClue("branch order, not finishing order, orders the errors") {
            accumulated shouldBe nonEmptyListOf(Bad("first"), Bad("second")).left()
        }
    }

    @Test
    fun `a raising branch does not interrupt its sibling`() {
        val raised = CountDownLatch(1)
        val bystander = Bystander()

        val accumulated = either<NonEmptyList<Bad>, String> {
            parZipOrAccumulate(
                {
                    raised.countDown()
                    raise(Bad("raised"))
                },
                { bystander.waitOutARaise(raised) },
            ) { _: Int, sibling: String -> sibling }
        }

        accumulated shouldBe nonEmptyListOf(Bad("raised")).left()
        withClue("every branch runs to completion, so a raise cannot end a sibling") {
            bystander.wasInterrupted() shouldBe false
        }
    }

    @Test
    fun `combine folds the raises into the single error the scope declares`() {
        val folded = either<Bad, Int> {
            parZipOrAccumulate(
                { left, right -> Bad(left.why + right.why) },
                { raise(Bad("a")) },
                { raise(Bad("b")) },
            ) { _: Int, _: Int -> 0 }
        }

        folded shouldBe Bad("ab").left()
    }

    @Test
    fun `parMapOrAccumulate keeps the raise of every element that raised`() {
        val accumulated = either<NonEmptyList<Bad>, List<Int>> {
            parMapOrAccumulate(listOf(1, 2, 3, 4)) { element ->
                if (element == 4) element else raise(Bad("element $element"))
            }
        }

        accumulated shouldBe nonEmptyListOf(Bad("element 1"), Bad("element 2"), Bad("element 3")).left()

        either<NonEmptyList<Bad>, List<Int>> {
            parMapOrAccumulate(listOf(1, 2, 3)) { it * 2 }
        } shouldBe listOf(2, 4, 6).right()
    }

    @Test
    fun `parMapOrAccumulate folds its raises with combine`() {
        val folded = either<Bad, List<Int>> {
            parMapOrAccumulate({ left, right -> Bad(left.why + right.why) }, listOf(1, 2)) { element ->
                raise(Bad("$element"))
            }
        }

        folded shouldBe Bad("12").left()

        either<Bad, List<Int>> {
            parMapOrAccumulate({ left, _ -> left }, listOf(1, 2)) { it * 2 }
        } shouldBe listOf(2, 4).right()
    }

    @Test
    fun `a throw among the raises rethrows the same instance and interrupts the rest`() {
        val boom = Boom()
        val sleeper = Sleeper()

        val thrown = shouldThrow<Boom> {
            either<NonEmptyList<Bad>, Int> {
                parZipOrAccumulate(
                    { raise(Bad("raised")) },
                    { sleeper.body() },
                    {
                        sleeper.awaitStart()
                        throw boom
                    },
                ) { _: Int, _: String, _: Int -> 0 }
            }
        }

        thrown shouldBeSameInstanceAs boom
        withClue("a throw is not accumulated: it ends the others as parZip's does") {
            sleeper.wasInterrupted() shouldBe true
        }
        sleeper.isAlive() shouldBe false
    }

    @Test
    fun `parZipOrAccumulate combines its branches at every arity`() {
        either<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }) { a, b -> a + b }
        } shouldBe "ab".right()

        either<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }, { "c" }) { a, b, c -> a + b + c }
        } shouldBe "abc".right()

        either<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }) { a, b, c, d -> a + b + c + d }
        } shouldBe "abcd".right()

        either<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e ->
                a + b + c + d + e
            }
        } shouldBe "abcde".right()

        either<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }) { a, b, c, d, e, f ->
                a + b + c + d + e + f
            }
        } shouldBe "abcdef".right()

        either<NonEmptyList<Bad>, String> {
            parZipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }) { a, b, c, d, e, f, g ->
                a + b + c + d + e + f + g
            }
        } shouldBe "abcdefg".right()

        either<NonEmptyList<Bad>, String> {
            parZipOrAccumulate(
                { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" },
            ) { a, b, c, d, e, f, g, h -> a + b + c + d + e + f + g + h }
        } shouldBe "abcdefgh".right()
    }

    @Test
    fun `a nine-branch parZipOrAccumulate answers, and keeps all nine raises`() {
        either<NonEmptyList<Bad>, String> {
            parZipOrAccumulate(
                { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }, { "i" },
            ) { a, b, c, d, e, f, g, h, i -> a + b + c + d + e + f + g + h + i }
        } shouldBe "abcdefghi".right()

        val allNine = either<NonEmptyList<Bad>, String> {
            parZipOrAccumulate(
                { raise(Bad("1")) },
                { raise(Bad("2")) },
                { raise(Bad("3")) },
                { raise(Bad("4")) },
                { raise(Bad("5")) },
                { raise(Bad("6")) },
                { raise(Bad("7")) },
                { raise(Bad("8")) },
                { raise(Bad("9")) },
            ) { _: String, _: String, _: String, _: String, _: String,
                _: String, _: String, _: String, _: String,
                ->
                ""
            }
        }

        allNine shouldBe NonEmptyList(Bad("1"), (2..9).map { Bad("$it") }).left()
    }

    @Test
    fun `the combine form combines its branches at every arity`() {
        val worst: (Bad, Bad) -> Bad = { left, _ -> left }

        either<Bad, String> { parZipOrAccumulate(worst, { "a" }, { "b" }) { a, b -> a + b } } shouldBe "ab".right()

        either<Bad, String> {
            parZipOrAccumulate(worst, { "a" }, { "b" }, { "c" }) { a, b, c -> a + b + c }
        } shouldBe "abc".right()

        either<Bad, String> {
            parZipOrAccumulate(worst, { "a" }, { "b" }, { "c" }, { "d" }) { a, b, c, d -> a + b + c + d }
        } shouldBe "abcd".right()

        either<Bad, String> {
            parZipOrAccumulate(worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e ->
                a + b + c + d + e
            }
        } shouldBe "abcde".right()

        either<Bad, String> {
            parZipOrAccumulate(worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }) { a, b, c, d, e, f ->
                a + b + c + d + e + f
            }
        } shouldBe "abcdef".right()

        either<Bad, String> {
            parZipOrAccumulate(
                worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" },
            ) { a, b, c, d, e, f, g -> a + b + c + d + e + f + g }
        } shouldBe "abcdefg".right()

        either<Bad, String> {
            parZipOrAccumulate(
                worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" },
            ) { a, b, c, d, e, f, g, h -> a + b + c + d + e + f + g + h }
        } shouldBe "abcdefgh".right()

        either<Bad, String> {
            parZipOrAccumulate(
                worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }, { "f" }, { "g" }, { "h" }, { "i" },
            ) { a, b, c, d, e, f, g, h, i -> a + b + c + d + e + f + g + h + i }
        } shouldBe "abcdefghi".right()
    }
}
