package io.github.matthewjones372.lark.assert

import org.junit.jupiter.api.Test

@OptIn(FailureSeam::class)
class MissTest {

    @Test
    fun `a check that holds answers null, and one that does not answers what it found`() {
        1.notEqual(1).shouldBeNull()
        1.notEqual(2) shouldBe Miss(expected = 2, actual = 1)
        3.notAtMost(2) shouldBe Miss(expected = null, actual = 3, expectedText = "at most 2")
        "rex".notInstanceOf<String>().shouldBeNull()
    }
}
