package io.github.matthewjones372.surefold.junit.samples

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.story
import io.github.matthewjones372.surefold.test.eventually
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Checks waited on until they hold, as a projection catching up is. */
object WaitSuite : Suite() {
    init {
        test("holds once its check does, on the third try") {
            val reads = AtomicInteger(0)
            eventually(1.seconds, every = 1.milliseconds) { reads.incrementAndGet() shouldBe 3 }
        }
        test("gives up on a check that never holds") {
            eventually(50.milliseconds, every = 10.milliseconds) { "Adopting" shouldBe "Adopted" }
        }
        test("waits as a story's step") {
            val reads = AtomicInteger(0)
            story {
                Then("the third read sees it").eventually(1.seconds, every = 1.milliseconds) {
                    reads.incrementAndGet() shouldBe 3
                }
            }
        }
        test("tries again after a throw") {
            val reads = AtomicInteger(0)
            eventually(1.seconds, every = 1.milliseconds) {
                check(reads.incrementAndGet() > 1) { "the server is still starting" }
                reads.get() shouldBe 2
            }
        }
    }
}
