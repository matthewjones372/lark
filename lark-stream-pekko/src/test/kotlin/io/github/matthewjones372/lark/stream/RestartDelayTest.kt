package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Schedule
import org.apache.pekko.actor.testkit.typed.javadsl.ManualTime
import org.apache.pekko.stream.testkit.javadsl.TestSink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * That a restart on Pekko waits for the delay its schedule decided, on a clock the test moves.
 * `TestStreamsTest` says the same of the test backend; this is the `initialDelay` Pekko's own
 * restart is built on, which nothing else times.
 *
 * A class of its own because `ManualTime` replaces the system's scheduler, and every timer in any
 * other test on this system would stop with it.
 */
class RestartDelayTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-restart-delay-test", ManualTime.config())

        private val delay = 10.seconds
    }

    private val clock: ManualTime get() = ManualTime.get(pekko.typed)

    /** Long enough for an element that was going to arrive to arrive; the scheduled clock does not move. */
    private val quiet = 300.milliseconds.toJavaDuration()

    @Test
    fun `nothing is emitted again until the schedule's delay has passed`() {
        val thirds = AtomicInteger()
        val probe = Stream.from(listOf(1, 2, 3))
            .map { n -> if (n == 3 && thirds.getAndIncrement() == 0) error("ledger down") else n }
            .restartOnDefect(Schedule.spaced(delay))
            .toSource()
            .runWith(TestSink.probe(pekko.classic), pekko.system)

        probe.request(10)
        probe.expectNext(1, 2)
        probe.expectNoMessage(quiet)

        clock.timePasses((delay - 1.seconds).toJavaDuration())
        probe.expectNoMessage(quiet)

        clock.timePasses(1.seconds.toJavaDuration())
        probe.expectNext(1, 2, 3)
        probe.expectComplete()
    }
}
