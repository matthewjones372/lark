package io.github.matthewjones372.lark

import arrow.core.Either
import arrow.core.nonFatalOrThrow
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.recover
import io.github.matthewjones372.lark.Schedule.Decision.Continue
import io.github.matthewjones372.lark.Schedule.Decision.Done

/** Runs [action] again on every throw the schedule accepts, and rethrows the last one when it ends. */
fun <A> Schedule<Throwable, *>.retry(action: () -> A): A = retryOrElse(action) { failure, _ -> throw failure }

/**
 * The same, answering with [orElse] of the last throw and the schedule's last output instead of rethrowing.
 * A throw the JVM cannot be asked to carry on from — and a `raise` passing through — is never retried.
 */
fun <Output, A> Schedule<Throwable, Output>.retryOrElse(action: () -> A, orElse: (Throwable, Output) -> A): A {
    var next = step
    while (true) {
        try {
            return action()
        } catch (failure: Throwable) {
            when (val decision = next(failure.nonFatalOrThrow())) {
                is Continue -> {
                    decision.delay.sleepOff()
                    next = decision.step
                }

                is Done -> return orElse(failure, decision.output)
            }
        }
    }
}

/** Runs [action] again on every raise the schedule accepts, and re-raises the last one when it ends. */
fun <E, Output, A> Raise<E>.retry(schedule: Schedule<E, Output>, action: Raise<E>.() -> A): A {
    var next = schedule.step
    while (true) {
        recover({ return action(this) }) { error ->
            when (val decision = next(error)) {
                is Continue -> {
                    decision.delay.sleepOff()
                    next = decision.step
                }

                is Done -> raise(error)
            }
        }
    }
}

/** The same retry with a boundary of its own, so the last raise is the `Left` rather than the caller's. */
fun <E, A, Output> Schedule<E, Output>.retryRaise(action: Raise<E>.() -> A): Either<E, A> = either {
    retry(this@retryRaise, action)
}
