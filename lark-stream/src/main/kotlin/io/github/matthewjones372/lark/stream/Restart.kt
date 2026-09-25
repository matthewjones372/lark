package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.logger

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
