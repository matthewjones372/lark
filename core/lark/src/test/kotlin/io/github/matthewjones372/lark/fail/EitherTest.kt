package io.github.matthewjones372.lark.fail

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EitherTest {

    private val right: Either<String, Int> = 1.right()
    private val left: Either<String, Int> = "e".left()

    @Test
    fun `fold takes the side the value is on`() {
        right.fold({ "left $it" }, { "right $it" }) shouldBe "right 1"
        left.fold({ "left $it" }, { "right $it" }) shouldBe "left e"
    }

    @Test
    fun `map and flatMap touch only a right`() {
        right.map { it + 1 } shouldBe 2.right()
        left.map { it + 1 } shouldBe left
        right.flatMap { "stop".left() } shouldBe "stop".left()
        left.flatMap { 3.right() } shouldBe left
    }

    @Test
    fun `mapLeft touches only a left`() {
        left.mapLeft { it.length } shouldBe 1.left()
        right.mapLeft { it.length } shouldBe right
    }

    @Test
    fun `the getters answer null on the other side`() {
        right.getOrNull() shouldBe 1
        right.leftOrNull() shouldBe null
        left.getOrNull() shouldBe null
        left.leftOrNull() shouldBe "e"
        left.getOrElse { it.length } shouldBe 1
        right.isRight() shouldBe true
        left.isLeft() shouldBe true
    }

    @Test
    fun `onLeft and onRight run only for their own side`() {
        val seen = StringBuilder()
        right.onLeft { seen.append("L") }.onRight { seen.append("R$it") }
        left.onLeft { seen.append("L$it") }.onRight { seen.append("R") }
        seen.toString() shouldBe "R1Le"
    }

    @Test
    fun `swap and merge`() {
        right.swap() shouldBe 1.left()
        "same".left().merge() shouldBe "same"
    }
}
