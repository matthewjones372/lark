package io.github.matthewjones372.lark

import arrow.core.NonEmptyList
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AccumulateTest {

    @Test
    fun `parZipOrAccumulate combines six to eight branches`() {
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
    fun `the combine form combines six to nine branches`() {
        val worst: (Bad, Bad) -> Bad = { left, _ -> left }

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
