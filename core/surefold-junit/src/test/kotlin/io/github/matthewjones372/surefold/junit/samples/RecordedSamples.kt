package io.github.matthewjones372.surefold.junit.samples

import io.github.matthewjones372.surefold.flock
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.story
import java.util.concurrent.atomic.AtomicReference

/** Checks recorded as they are made, wherever in the test they are made. */
object RecordedSuite : Suite() {
    init {
        test("records each check written as a statement") {
            2 shouldBe 3
            2 shouldBe 2
            expect(2 + 2 == 5)
        }
        test("fails a test that checks nothing") {
            println("nothing checked")
        }
        test("counts a story step left out of any value") {
            story {
                Then("it is three") { 2 shouldBe 3 }
                Then("it is two") { 2 shouldBe 2 }
            }
        }
        test("records a check made on a fork") {
            flock<Nothing, Unit> { async { 2 shouldBe 4 } }
        }
        test("throws a check made on a thread surefold does not carry, there") {
            val thrown = AtomicReference<Throwable>()
            val bare = Thread {
                try {
                    val _ = 2 shouldBe 2
                } catch (refused: IllegalStateException) {
                    thrown.set(refused)
                }
            }
            bare.start()
            bare.join()
            expect(thrown.get() is IllegalStateException)
        }
    }
}
