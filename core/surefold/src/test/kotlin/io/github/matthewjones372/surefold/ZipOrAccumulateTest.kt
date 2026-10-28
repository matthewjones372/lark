package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeLeft
import io.github.matthewjones372.surefold.assert.shouldBeRight
import io.github.matthewjones372.surefold.assert.shouldThrow
import io.github.matthewjones372.surefold.fail.NonEmptyList
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.nonEmptyListOf
import org.junit.jupiter.api.Test

class ZipOrAccumulateTest {

    @Test
    fun `two failing branches answer with a NonEmptyList of both, in branch order`() {
        attempt<NonEmptyList<Bad>, Int> {
            zipOrAccumulate(
                { fail(Bad("first")) },
                { fail(Bad("second")) },
            ) { _: Int, _: Int -> 0 }
        } shouldBeLeft nonEmptyListOf(Bad("first"), Bad("second"))
    }

    @Test
    fun `every branch runs on the calling thread, after the one before it`() {
        val caller = Thread.currentThread()
        val ran = mutableListOf<String>()

        attempt<NonEmptyList<Bad>, Int> {
            zipOrAccumulate(
                {
                    ran += "a"
                    Thread.currentThread() shouldBe caller
                    fail(Bad("a"))
                },
                {
                    ran += "b"
                    Thread.currentThread() shouldBe caller
                    1
                },
            ) { _: Int, b: Int -> b }
        } shouldBeLeft nonEmptyListOf(Bad("a"))
        ran shouldBe listOf("a", "b")
    }

    @Test
    fun `a throw ends the call where it happens and is rethrown as the same instance`() {
        val boom = Boom()
        var secondRan = false

        val thrown = shouldThrow<Boom> {
            attempt<NonEmptyList<Bad>, Int> {
                zipOrAccumulate(
                    { throw boom },
                    { secondRan = true },
                ) { _: Int, _: Unit -> 0 }
            }
        }

        (thrown === boom) shouldBe true
        secondRan shouldBe false
    }

    @Test
    fun `a null error is kept like any other`() {
        attempt<NonEmptyList<Bad?>, Int> {
            zipOrAccumulate({ fail(null) }, { fail(Bad("b")) }) { _: Int, _: Int -> 0 }
        } shouldBeLeft nonEmptyListOf(null, Bad("b"))
    }

    @Test
    fun `combine folds the failures into the single error the scope declares`() {
        attempt<Bad, Int> {
            zipOrAccumulate(
                { left, right -> Bad(left.why + right.why) },
                { fail(Bad("a")) },
                { 1 },
                { fail(Bad("c")) },
            ) { _: Int, _: Int, _: Int -> 0 }
        } shouldBeLeft Bad("ac")
    }

    @Test
    fun `zipOrAccumulate combines two to five branches`() {
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }) { a, b -> a + b }
        } shouldBeRight "ab"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }, { "c" }) { a, b, c -> a + b + c }
        } shouldBeRight "abc"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }) { a, b, c, d -> a + b + c + d }
        } shouldBeRight "abcd"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate({ "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e -> a + b + c + d + e }
        } shouldBeRight "abcde"
        attempt<NonEmptyList<Bad>, String> {
            zipOrAccumulate(
                { fail(Bad("1")) },
                { "b" },
                { fail(Bad("3")) },
                { "d" },
                { fail(Bad("5")) },
            ) { a: String, b, c: String, d, e: String -> a + b + c + d + e }
        } shouldBeLeft nonEmptyListOf(Bad("1"), Bad("3"), Bad("5"))
    }

    @Test
    fun `the combine form combines two to five branches`() {
        val worst: (Bad, Bad) -> Bad = { left, _ -> left }

        attempt<Bad, String> { zipOrAccumulate(worst, { "a" }, { "b" }) { a, b -> a + b } } shouldBeRight "ab"
        attempt<Bad, String> {
            zipOrAccumulate(worst, { "a" }, { "b" }, { "c" }) { a, b, c -> a + b + c }
        } shouldBeRight "abc"
        attempt<Bad, String> {
            zipOrAccumulate(worst, { "a" }, { "b" }, { "c" }, { "d" }) { a, b, c, d -> a + b + c + d }
        } shouldBeRight "abcd"
        attempt<Bad, String> {
            zipOrAccumulate(worst, { "a" }, { "b" }, { "c" }, { "d" }, { "e" }) { a, b, c, d, e ->
                a + b + c + d + e
            }
        } shouldBeRight "abcde"
        attempt<Bad, String> {
            zipOrAccumulate(worst, { "a" }, { fail(Bad("b")) }, { "c" }, { "d" }, { fail(Bad("e")) }) {
                    a, b: String, c, d, e: String ->
                a + b + c + d + e
            }
        } shouldBeLeft Bad("b")
    }
}
