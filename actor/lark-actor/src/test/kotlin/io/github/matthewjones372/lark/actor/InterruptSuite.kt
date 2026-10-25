package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.resource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.hours

private sealed interface Chore

/** Blocks until a latch nobody counts down, after saying it has started. */
private data class Forever(val started: CountDownLatch) : Chore

/** Raises, after saying it is about to. */
private data class Fumble(val raising: CountDownLatch) : Chore

private fun chores(log: AtomicReference<List<String>>) = behaviour<Chore, Unit, String>(Unit) { _, _, message ->
    when (message) {
        is Forever -> {
            message.started.countDown()
            CountDownLatch(1).await()
            stay()
        }

        is Fumble -> {
            message.raising.countDown()
            fail("fumbled")
        }
    }
}.onSignal { _, _, signal ->
    if (signal == Signal.Stopping) log.updateAndGet { it + "stopping" }
    stay()
}

object InterruptSuite : Suite<Unit>() {
    override val shared: Resource<Unit> = resource { }

    init {
        test("closing the flock interrupts a step blocked forever, and the actor still takes its Stopping") { _ ->
            val log = AtomicReference<List<String>>(emptyList())

            flock<Nothing, Unit> {
                val started = CountDownLatch(1)
                spawn("chores", chores(log)).tell(Forever(started))
                started.await()
            }

            log.get() shouldBe listOf("stopping")
        }

        test("closing the flock cuts a restart's delay short") { _ ->
            val log = AtomicReference<List<String>>(emptyList())

            flock<Nothing, Unit> {
                val raising = CountDownLatch(1)
                spawn("chores", chores(log), restart = Schedule.spaced(1.hours)).tell(Fumble(raising))
                raising.await()
            }

            log.get() shouldBe listOf("stopping")
        }

        test("a parent's stop interrupts a child blocked forever") { _ ->
            val log = AtomicReference<List<String>>(emptyList())

            flock<Nothing, Unit> {
                val started = CountDownLatch(1)
                val parent = spawn(
                    "parent",
                    behaviour<Unit, Unit>(Unit) { ctx, _, _ ->
                        ctx.spawn("child", chores(log)).tell(Forever(started))
                        started.await()
                        stop()
                    },
                )
                parent.tell(Unit)
                watch(parent).await()
            }

            log.get() shouldBe listOf("stopping")
        }
    }
}
