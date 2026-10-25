package io.github.matthewjones372.surefold.stream

import io.github.matthewjones372.surefold.Clock
import io.github.matthewjones372.surefold.LogLevel
import io.github.matthewjones372.surefold.LogLine
import io.github.matthewjones372.surefold.Logger
import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.ScheduleStep
import org.apache.pekko.NotUsed
import org.apache.pekko.japi.pf.PFBuilder
import org.apache.pekko.stream.Graph
import org.apache.pekko.stream.SourceShape
import org.apache.pekko.stream.javadsl.Source
import kotlin.time.Duration
import kotlin.time.toJavaDuration

internal class Restarts(private val to: Logger, private val by: Clock) {

    fun warn(defect: Throwable, delay: Duration) =
        to.log(LogLine(LogLevel.Warn, "surefold-stream: restarting in $delay after $defect", by.now(), defect))
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
