package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Limit
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Streams under a [Limit] (spec 0171): one limit bounds every stream that draws on it, together. */
class LimitedTest {

    @Test
    fun `two streams sharing one limit never have more inside it, between them, than it allows`() {
        val dependency = Limit.fixed(3)
        val now = AtomicInteger()
        val most = AtomicInteger()
        fun reads(): Running<Nothing, Int> = Stream.from(1..100)
            .mapPar(dependency) { n ->
                most.accumulateAndGet(now.incrementAndGet(), ::maxOf)
                Thread.sleep(1)
                now.decrementAndGet()
                n
            }
            .runFold(0) { sum, n -> sum + n }
            .start(Forks())

        val first = reads()
        val second = reads()

        first.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done((1..100).sum())
        second.exit.toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done((1..100).sum())
        withClue("the most calls inside the shared limit at once") { (most.get() <= 3) shouldBe true }
        dependency.inFlight shouldBe 0
    }

    private companion object {
        const val SETTLE_SECONDS = 30L
    }
}
