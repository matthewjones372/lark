package io.github.matthewjones372.lark.assert

import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.fail.right
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EitherMatchersTest {

    private data class Bad(val why: String)

    private val right: Either<Bad, Int> = 42.right()
    private val left: Either<Bad, Int> = Bad("broken").left()

    @Test
    fun `shouldBeRight returns the value of a Right`() {
        assertEquals(42, right.shouldBeRight())
        assertEquals(42, right shouldBeRight 42)
    }

    @Test
    fun `shouldBeLeft returns the error of a Left`() {
        assertEquals(Bad("broken"), left.shouldBeLeft())
        assertEquals(Bad("broken"), left shouldBeLeft Bad("broken"))
    }

    @Test
    fun `a failure says what was expected and what was found, then where and the line itself`() {
        val failed = assertFailsWith<AssertionFailedError> { left shouldBeRight 42 }

        assertEquals(
            """
            expected: Right(value=42)
            but was:  Left(value=Bad(why=broken))
            at EitherMatchersTest.kt:${lineOf("{ left shouldBeRight 42 }")}
                val failed = assertFailsWith<AssertionFailedError> { left shouldBeRight 42 }
            """.trimIndent(),
            failed.message,
        )
    }

    @Test
    fun `a failure's stack starts at the test, not inside the matcher`() {
        val failed = assertFailsWith<AssertionFailedError> { right.shouldBeLeft() }

        val top = failed.stackTrace.first()
        assertEquals("EitherMatchersTest.kt", top.fileName)
        assertEquals(lineOf("{ right.shouldBeLeft() }"), top.lineNumber)
    }

    @Test
    fun `a failure carries expected and actual for the IDE to compare`() {
        val failed = assertFailsWith<AssertionFailedError> { right shouldBeLeft Bad("broken") }

        assertEquals("Left(value=Bad(why=broken))", failed.expected.stringRepresentation)
        assertEquals("Right(value=42)", failed.actual.stringRepresentation)
    }

    @Test
    fun `asserting only the side names the side expected`() {
        val failed = assertFailsWith<AssertionFailedError> { left.shouldBeRight() }

        assertEquals(
            listOf("expected: a Right", "but was:  Left(value=Bad(why=broken))"),
            failed.message!!.lines().take(2),
        )
    }

    @Test
    fun `values that print alike but differ in type are shown with their types`() {
        val long: Either<Bad, Any> = 42L.right()

        val failed = assertFailsWith<AssertionFailedError> { long shouldBeRight 42 }

        assertEquals(
            listOf("expected: Right(value=42) of kotlin.Int", "but was:  Right(value=42) of kotlin.Long"),
            failed.message!!.lines().take(2),
        )
    }

    @Test
    fun `a Right holding null is not taken for a Left`() {
        val nothing: Either<Bad, Int?> = null.right()

        assertEquals(null, nothing shouldBeRight null)
        assertFailsWith<AssertionFailedError> { nothing.shouldBeLeft() }
    }

    /** The line of this file that holds [text], as a stack frame numbers it. */
    private fun lineOf(text: String): Int =
        File("src/test/kotlin/io/github/matthewjones372/lark/assert/EitherMatchersTest.kt")
            .readLines()
            .indexOfFirst { text in it && "lineOf" !in it } + 1
}
