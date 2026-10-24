package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.assert.shouldBe
import io.github.matthewjones372.lark.assert.shouldBeLessThanOrEqual
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

class RunnersTest {

    /** Spec 0104: an urgent activation is taken before every ordinary one already waiting. */
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

    /** Spec 0143: a runner that runs out of work waits to be woken rather than leaving for a new one to start. */
    @Test
    fun `activations submitted one after another start no more runners than the parallelism`() {
        val runners = Runners(parallelism = 4, cap = 4)
        repeat(10_000) {
            val ran = CountDownLatch(1)
            runners.submit(Activation { ran.countDown() })
            ran.await()
        }
        runners.awaitIdle()

        withClue("runner threads started for 10,000 activations, each queued after the last had run") {
            runners.starts.get() shouldBeLessThanOrEqual 4
        }
    }
}
