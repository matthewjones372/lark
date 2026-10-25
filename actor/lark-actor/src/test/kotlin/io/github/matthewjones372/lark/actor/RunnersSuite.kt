package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldBeLessThanOrEqual
import io.github.matthewjones372.lark.junit.withClue
import io.github.matthewjones372.lark.resource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue

object RunnersSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        // Spec 0104: an urgent activation is taken before every ordinary one already waiting.
        test("an urgent activation runs ahead of the ordinary ones queued before it") { _ ->
            val runners = Runners(parallelism = 1, cap = 1)
            val release = CountDownLatch(1)
            val ran = LinkedBlockingQueue<String>()
            // The only runner is held, so everything submitted after this waits in the queues.
            runners.submit(Activation { release.await() })
            repeat(1_000) { n -> runners.submit(Activation { ran.put("ordinary $n") }) }
            runners.submit(Activation { ran.put("urgent") }, first = true)

            release.countDown()
            runners.awaitIdle()

            (ran.first() shouldBe "urgent") and (ran.size shouldBe 1_001)
        }

        // Spec 0143: a runner that runs out of work waits to be woken rather than leaving for a new one to start.
        test("activations submitted one after another start no more runners than the parallelism") { _ ->
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
}
