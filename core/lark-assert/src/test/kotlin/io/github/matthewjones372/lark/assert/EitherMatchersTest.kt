package io.github.matthewjones372.lark.assert

import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.left
import io.github.matthewjones372.lark.fail.right
import org.junit.jupiter.api.Test
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
    fun `shouldBeRight on a Left names the Left it found`() {
        val any = assertFailsWith<AssertionError> { left.shouldBeRight() }
        assertEquals("expected a Right but was Left(value=Bad(why=broken))", any.message)

        val exact = assertFailsWith<AssertionError> { left shouldBeRight 42 }
        assertEquals("expected Right(value=42) but was Left(value=Bad(why=broken))", exact.message)
    }

    @Test
    fun `shouldBeRight with another value names both`() {
        val wrong = assertFailsWith<AssertionError> { right shouldBeRight 7 }
        assertEquals("expected Right(value=7) but was Right(value=42)", wrong.message)
    }

    @Test
    fun `a Right holding null is not taken for a Left`() {
        val nothing: Either<Bad, Int?> = null.right()
        assertEquals(null, nothing shouldBeRight null)
        assertFailsWith<AssertionError> { nothing.shouldBeLeft() }
    }

    @Test
    fun `shouldBeLeft returns the error of a Left`() {
        assertEquals(Bad("broken"), left.shouldBeLeft())
        assertEquals(Bad("broken"), left shouldBeLeft Bad("broken"))
    }

    @Test
    fun `shouldBeLeft on a Right names the Right it found`() {
        val any = assertFailsWith<AssertionError> { right.shouldBeLeft() }
        assertEquals("expected a Left but was Right(value=42)", any.message)

        val exact = assertFailsWith<AssertionError> { right shouldBeLeft Bad("broken") }
        assertEquals("expected Left(value=Bad(why=broken)) but was Right(value=42)", exact.message)
    }

    @Test
    fun `shouldBeLeft with another error names both`() {
        val wrong = assertFailsWith<AssertionError> { left shouldBeLeft Bad("other") }
        assertEquals("expected Left(value=Bad(why=other)) but was Left(value=Bad(why=broken))", wrong.message)
    }
}
