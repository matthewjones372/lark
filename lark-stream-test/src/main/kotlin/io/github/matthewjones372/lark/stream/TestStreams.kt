package io.github.matthewjones372.lark.stream

import io.github.matthewjones372.lark.TestClock
import java.util.concurrent.Executor

/**
 * A backend for tests: every stage runs on the calling thread, so `run` returns with the exit already
 * complete and nothing is left running behind the assertion that follows it.
 *
 * It is the [Forks] pull loop, run in place, and so runs the operators Forks does and refuses the same
 * ones, by its own name. [clock] is the time `tick`, `groupedWithin` and restarts will read once spec
 * 0048's second entry lands. A stream that never ends and never meets a `take` blocks the test that
 * started it, because there is no other thread to leave it on.
 */
class TestStreams(val clock: TestClock = TestClock()) : StreamBackend by Forks(CallingThread, name = "TestStreams")

/** Runs what it is given before `execute` returns. */
private object CallingThread : Executor {
    override fun execute(command: Runnable) = command.run()
}
