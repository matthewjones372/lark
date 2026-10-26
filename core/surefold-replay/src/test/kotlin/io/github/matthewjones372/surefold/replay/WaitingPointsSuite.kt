package io.github.matthewjones372.surefold.replay

import io.github.matthewjones372.surefold.TestClock
import io.github.matthewjones372.surefold.channel
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldContain
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.junit.withClue
import io.github.matthewjones372.surefold.scope
import io.github.matthewjones372.surefold.select
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * The planted ordering bug: the producer logs that it sent before the consumer logs what it got, which holds only when
 * the consumer reached the channel first. On a channel with no room, whoever arrives second hands over and runs on.
 */
private fun sentBeforeGot() {
    val log = Collections.synchronizedList(mutableListOf<String>())
    scope {
        val rows = channel<Int>(0)
        val producer = async {
            rows.send(1)
            log += "sent"
            rows.close()
        }
        val consumer = async { for (row in rows) log += "got $row" }
        producer.await()
        consumer.await()
    }
    check(log == listOf("sent", "got 1")) { "the log is $log" }
}

private val named = Regex("""replay\((0x[0-9a-f]+)\)""")

/** Spec 0042: channels, `select` and `TestClock` sleeps as waiting points, and a run that leaves determinism. */
object WaitingPointsSuite : Suite() {
    init {
        test("a producer and consumer ordering bug is found, and its order fails every time it is replayed") {
            shouldThrow<AssertionError>({ explore(runs = 1_000) { sentBeforeGot() } }) { broke ->
                val order = named.find(broke.message.orEmpty())?.groupValues?.get(1)
                withClue(broke.message.orEmpty()) { (order != null) shouldBe true }
                val seed = checkNotNull(order).removePrefix("0x").toLong(radix = 16)
                val failed = (1..20).count {
                    try {
                        replay(seed) { sentBeforeGot() }
                        false
                    } catch (_: IllegalStateException) {
                        true
                    }
                }
                failed shouldBe 20
            }
        }

        test("a select between a channel and a fork answers in an order the seed decides") {
            fun answered(order: Long): String {
                var answer = ""
                replay(order) {
                    scope {
                        val rows = channel<String>(1)
                        val sender = async { rows.send("row") }
                        val fork = async { "fork" }
                        answer = select {
                            rows.onReceive { it.orEmpty() }
                            fork.onAwait { it }
                        }
                        sender.await()
                        fork.await()
                    }
                }
                return answer
            }

            val answers = (1L..20L).associateWith(::answered)
            answers.forEach { (order, answer) -> answered(order) shouldBe answer }
            withClue("the answers across twenty orders: $answers") { answers.values.toSet().size shouldBe 2 }
        }

        test("a fork sleeping on a TestClock waits as a waiting point, so the test moving the clock runs") {
            var runs = 0
            explore(runs = 50) {
                runs++
                val moving = TestClock()
                clock.locally(moving) {
                    scope {
                        val sleeper = async { clock.get().sleep(5.seconds) }
                        moving.adjustWhenBlocked(5.seconds)
                        sleeper.await()
                    }
                }
            }
            runs shouldBe 50
        }

        test("a Thread.sleep inside a fork fails the run, naming where that fork waited") {
            shouldThrow<AssertionError>({
                explore(runs = 1) {
                    scope { async { Thread.sleep(2_000) }.await() }
                }
            }) { broke ->
                val left = broke.cause?.message.orEmpty()
                left shouldContain "the run left determinism"
                left shouldContain "held the turn waiting outside surefold"
                left shouldContain "Thread.sleep"
            }
        }
    }
}
