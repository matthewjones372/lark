package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.capturingMetrics
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Fails once when told to. */
private data object MeteredTrip

class ActorMetricsTest {

    @Test
    fun `a tell to a stopped actor and a restart are each counted once, with the flock's tags`() {
        capturingMetrics { captured ->
            flock<Nothing, Unit> {
                tagMetrics("node" to "shop-1")
                val quiet = spawn("quiet", behaviour<MeteredTrip, Unit>(Unit) { _, _, _ -> stay() })
                stop(quiet).await()
                quiet.tell(MeteredTrip)

                val tripping = spawn(
                    "tripping",
                    behaviour<MeteredTrip, Unit>(Unit) { _, _, _ -> error("tripped") },
                    restart = Schedule.recurs(1),
                )
                tripping.tell(MeteredTrip)
                awaitIdle()
            }

            captured.counter("lark.actor.dead_letters") shouldBe 1.0
            captured.tags("lark.actor.dead_letters") shouldBe mapOf("node" to "shop-1", "reason" to "stopped")
            captured.counter("lark.actor.restarts") shouldBe 1.0
            captured.tags("lark.actor.restarts") shouldBe mapOf("node" to "shop-1")
        }
    }
}
