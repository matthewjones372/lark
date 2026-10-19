package io.github.matthewjones372.lark.pekko

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.apache.pekko.dispatch.Futures
import org.junit.jupiter.api.Test
import scala.concurrent.Future

private class Boom : RuntimeException("boom")

class AwaitTest {

    @Test
    fun `a Scala future round-trips, value and failure`() {
        Future.successful(42).await() shouldBe 42

        val boom = Boom()
        // Pekko's Java API for a failed future: `Future.failed` is an instance method of the trait too,
        // so the companion's has no static forwarder for Kotlin to call.
        val thrown = shouldThrow<Boom> { Futures.failed<Int>(boom).await() }

        thrown shouldBeSameInstanceAs boom
    }
}
