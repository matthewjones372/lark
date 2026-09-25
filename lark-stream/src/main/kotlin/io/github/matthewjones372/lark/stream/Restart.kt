package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Clock
import io.github.matthewjones372.lark.LogLevel
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.Logger
import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.ScheduleStep
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.logger
import org.apache.pekko.NotUsed
import org.apache.pekko.japi.pf.PFBuilder
import org.apache.pekko.stream.Graph
import org.apache.pekko.stream.SourceShape
import org.apache.pekko.stream.javadsl.Source
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * On a defect, this same description materialised again after the delay [schedule] decides. A declared
 * failure passes through as it is, and a schedule that is done lets the defect through as `Died`.
 *
 * What was emitted before the defect stays emitted: a restarted `Stream.from` emits its elements again.
 */
fun <E, A : Any> Stream<E, A>.restartOnDefect(schedule: Schedule<Throwable, *>): Stream<E, A> =
    // Bound here rather than read later: a restart is decided on a Pekko thread, which inherits
    // neither the logger nor the clock of the code that built the stream.
    Stream(Node.RestartOnDefect(node, schedule.step, logger.get(), clock.get()))

internal class Restarts(private val to: Logger, private val by: Clock) {

    fun warn(defect: Throwable, delay: Duration) =
        to.log(LogLine(LogLevel.Warn, "lark-stream: restarting in $delay after $defect", by.now(), defect))
}

internal fun <A : Any> restarting(
    source: Source<A, NotUsed>,
    step: ScheduleStep<Throwable, *>,
    restarts: Restarts,
): Source<A, NotUsed> =
    source.recoverWithRetries(
        1,
        PFBuilder<Throwable, Graph<SourceShape<A>, NotUsed>>()
            .match(Throwable::class.java, { thrown -> thrown !is DeclaredFailure }) { defect ->
                when (val decision = step(defect)) {
                    is Schedule.Decision.Continue -> {
                        restarts.warn(defect, decision.delay)
                        restarting(source, decision.step, restarts).initialDelay(decision.delay.toJavaDuration())
                    }

                    is Schedule.Decision.Done -> Source.failed(defect)
                }
            }
            .build(),
    )
