package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.capturingMetrics
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe

/** Fails once when told to. */
private data object MeteredTrip

object ActorMetricsSuite : Suite() {
    init {
        test("a tell to a stopped actor and a restart are each counted once, with the flock's tags") {
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

                (captured.counter("lark.actor.dead_letters") shouldBe 1.0) and
                    (
                        captured.tags("lark.actor.dead_letters") shouldBe
                            mapOf("node" to "shop-1", "reason" to "stopped")
                        ) and
                    (captured.counter("lark.actor.restarts") shouldBe 1.0) and
                    (captured.tags("lark.actor.restarts") shouldBe mapOf("node" to "shop-1"))
            }
        }

        test("a dead letter counted before the flock is tagged is counted with the tags once it is") {
            capturingMetrics { captured ->
                flock<Nothing, Unit> {
                    val quiet = spawn("quiet", behaviour<MeteredTrip, Unit>(Unit) { _, _, _ -> stay() })
                    stop(quiet).await()
                    quiet.tell(MeteredTrip)
                    tagMetrics("node" to "shop-1")
                    quiet.tell(MeteredTrip)
                }

                captured.tags("lark.actor.dead_letters") shouldBe mapOf("node" to "shop-1", "reason" to "stopped")
            }
        }
    }
}
