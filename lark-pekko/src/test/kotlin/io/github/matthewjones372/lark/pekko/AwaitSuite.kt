package io.github.matthewjones372.lark.pekko

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeSameInstanceAs
import io.github.matthewjones372.lark.junit.shouldThrow
import io.github.matthewjones372.lark.resource
import org.apache.pekko.dispatch.Futures
import scala.concurrent.Future

private class Boom : RuntimeException("boom")

object AwaitSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("a Scala future round-trips, value and failure") { _ ->
            val boom = Boom()
            // Pekko's Java API for a failed future: `Future.failed` is an instance method of the trait too,
            // so the companion's has no static forwarder for Kotlin to call.
            (Future.successful(42).await() shouldBe 42) and
                shouldThrow<Boom>({ Futures.failed<Int>(boom).await() }) { thrown ->
                    thrown shouldBeSameInstanceAs boom
                }
        }
    }
}
