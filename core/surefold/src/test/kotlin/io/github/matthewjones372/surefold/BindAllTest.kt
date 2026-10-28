package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.assert.shouldBeLeft
import io.github.matthewjones372.surefold.assert.shouldBeRight
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.NonEmptyList
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.bindAll
import io.github.matthewjones372.surefold.fail.left
import io.github.matthewjones372.surefold.fail.nonEmptyListOf
import io.github.matthewjones372.surefold.fail.right
import org.junit.jupiter.api.Test

class BindAllTest {

    private fun check(n: Int): Either<NonEmptyList<Bad>, Int> =
        if (n < 0) nonEmptyListOf(Bad("$n low"), Bad("$n odd")).left() else n.right()

    @Test
    fun `bindAll in a map element keeps every error of the nested list, in order`() {
        attempt<NonEmptyList<Bad>, List<Int>> {
            mapOrAccumulate(listOf(-1, 2, -3)) { n -> check(n).bindAll() }
        } shouldBeLeft nonEmptyListOf(Bad("-1 low"), Bad("-1 odd"), Bad("-3 low"), Bad("-3 odd"))
    }

    @Test
    fun `bindAll in a zip branch keeps every error, beside a plain fail`() {
        attempt<NonEmptyList<Bad>, Int> {
            zipOrAccumulate({ fail(Bad("first")) }, { check(-2).bindAll() }) { a: Int, b: Int -> a + b }
        } shouldBeLeft nonEmptyListOf(Bad("first"), Bad("-2 low"), Bad("-2 odd"))
    }

    @Test
    fun `bindAll answers the value when the nested check passed`() {
        attempt<NonEmptyList<Bad>, List<Int>> {
            mapOrAccumulate(listOf(1, 2)) { n -> check(n).bindAll() }
        } shouldBeRight listOf(1, 2)
    }

    @Test
    fun `the combine form folds every error bindAll kept`() {
        attempt<Bad, List<Int>> {
            mapOrAccumulate({ left, right -> Bad(left.why + "," + right.why) }, listOf(-1)) { n -> check(n).bindAll() }
        } shouldBeLeft Bad("-1 low,-1 odd")
    }
}
