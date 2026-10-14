package io.github.matthewjones372.lark.actor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

/** Spec 0104: an urgent activation is taken before every ordinary one already waiting. */
class RunnersTest {

    @Test
    fun `an urgent activation runs ahead of the ordinary ones queued before it`() {
        val runners = Runners(parallelism = 1, cap = 1)
        val release = CountDownLatch(1)
        val ran = LinkedBlockingQueue<String>()
        // The only runner is held, so everything submitted after this waits in the queues.
        runners.submit(Activation { release.await() })
        repeat(1_000) { n -> runners.submit(Activation { ran.put("ordinary $n") }) }
        runners.submit(Activation { ran.put("urgent") }, first = true)

        release.countDown()
        runners.awaitIdle()

        ran.first() shouldBe "urgent"
        ran.size shouldBe 1_001
    }
}
