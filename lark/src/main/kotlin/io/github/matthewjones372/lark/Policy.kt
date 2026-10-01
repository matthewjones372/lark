package io.github.matthewjones372.lark

import arrow.core.raise.Raise
import io.github.matthewjones372.lark.Policy.Step
import io.github.matthewjones372.lark.Schedule.Decision.Continue
import io.github.matthewjones372.lark.Schedule.Decision.Done
import java.time.Instant
import java.util.concurrent.TimeoutException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.toKotlinDuration

/** A guard Lark cannot see into. It wraps whatever follows it in a [Policy], and refuses a call by throwing. */
interface Guard {
    fun <E, A> Raise<E>.guard(block: Raise<E>.() -> A): A
}

/** A call a guard refused to run, because it knew the call would fail or was told not to try it (spec 0110). */
sealed class Rejected(val guard: String, message: String) : RuntimeException(message) {
    class CircuitOpen(guard: String, val retryAt: Instant) : Rejected(guard, "$guard is open until $retryAt")

    class RateLimited(guard: String, val retryAfter: Duration) :
        Rejected(guard, "$guard has no token for $retryAfter")

    class BulkheadFull(guard: String) : Rejected(guard, "$guard has no permit free")
}

/** How a call is guarded: a chain of steps, checked once when it is built and read whole on every call. */
class Policy internal constructor(val name: String, val steps: List<Step>) {

    sealed interface Step {
        /** One budget for the whole call, retries included. */
        data class Deadline(val total: Duration) : Step

        data class Retry(val schedule: Schedule<Throwable, *>) : Step

        data class AttemptTimeout(val each: Duration) : Step

        data class Custom(val guard: Guard) : Step
    }

    // Without an attempt timeout of its own, an attempt is still cut to whatever the deadline leaves it.
    internal val cutsBeforeTheCall: Boolean = steps.none { it is Step.AttemptTimeout }

    operator fun <A> invoke(block: () -> A): A = Unraisable.runPolicy(this) { block() }
}

class PolicyBuilder internal constructor() {
    private val steps = mutableListOf<Step>()

    fun deadline(total: Duration) {
        steps += Step.Deadline(total)
    }

    fun retry(schedule: Schedule<Throwable, *>) {
        steps += Step.Retry(schedule)
    }

    fun attemptTimeout(each: Duration) {
        steps += Step.AttemptTimeout(each)
    }

    fun guard(guard: Guard) {
        steps += Step.Custom(guard)
    }

    internal fun build(name: String): Policy = steps.toList().also { checkOrder(name, it) }.let { Policy(name, it) }
}

/** Builds a policy, and throws [IllegalArgumentException] for steps out of order, repeated, or that cannot fit. */
fun policy(name: String, build: PolicyBuilder.() -> Unit): Policy = PolicyBuilder().apply(build).build(name)

/** Runs [block] under [policy], with a refusal answered as [ifRejected] of it rather than thrown. */
fun <E, A> Raise<E>.guarded(policy: Policy, ifRejected: (Rejected) -> E, block: Raise<E>.() -> A): A =
    try {
        runPolicy(policy, block)
    } catch (rejected: Rejected) {
        raise(ifRejected(rejected))
    }

// The order the runner reads a plan in. A guard Lark cannot see into has no place in it.
private enum class Kind(val label: String) {
    DEADLINE("deadline"),
    RETRY("retry"),
    ATTEMPT_TIMEOUT("attemptTimeout"),
}

private val Step.kind: Kind?
    get() = when (this) {
        is Step.Deadline -> Kind.DEADLINE
        is Step.Retry -> Kind.RETRY
        is Step.AttemptTimeout -> Kind.ATTEMPT_TIMEOUT
        is Step.Custom -> null
    }

private fun checkOrder(name: String, steps: List<Step>) {
    val expected = "Expected order: " + Kind.entries.joinToString(" → ") { it.label }
    steps.mapNotNull { it.kind }.zipWithNext().forEach { (before, after) ->
        require(before != after) { "Policy \"$name\": ${after.label} appears twice. $expected" }
        require(before < after) { "Policy \"$name\": ${after.label} comes after ${before.label}. $expected" }
    }
    val total = steps.firstNotNullOfOrNull { (it as? Step.Deadline)?.total }
    val each = steps.firstNotNullOfOrNull { (it as? Step.AttemptTimeout)?.each }
    require(total == null || each == null || each <= total) {
        "Policy \"$name\": an attempt timeout of $each is longer than its deadline of $total."
    }
}

/** What a call has left of its deadline, read from the clock each time it is asked. */
private class Budget(private val until: Instant?) {
    fun remaining(): Duration =
        until?.let { java.time.Duration.between(clock.get().now(), it).toKotlinDuration() } ?: Duration.INFINITE
}

private fun <E, A> Raise<E>.runPolicy(policy: Policy, block: Raise<E>.() -> A): A =
    from(policy, 0, Budget(null), block)

private fun <E, A> Raise<E>.from(policy: Policy, at: Int, budget: Budget, block: Raise<E>.() -> A): A {
    if (at == policy.steps.size) return if (policy.cutsBeforeTheCall) cut(budget.remaining(), block) else block()
    return when (val step = policy.steps[at]) {
        is Step.Deadline -> {
            val until = clock.get().now().plusNanos(step.total.inWholeNanoseconds)
            from(policy, at + 1, Budget(until), block)
        }

        is Step.Retry -> step.schedule.within(budget).retry { from(policy, at + 1, budget, block) }

        is Step.AttemptTimeout -> cut(minOf(step.each, budget.remaining())) { from(policy, at + 1, budget, block) }

        is Step.Custom -> with(step.guard) { guard { from(policy, at + 1, budget, block) } }
    }
}

// One fork for an attempt, and none at all when nothing bounds it.
private fun <E, A> Raise<E>.cut(limit: Duration, block: Raise<E>.() -> A): A = when {
    limit <= ZERO -> throw TimeoutException("the deadline has passed")
    limit.isInfinite() -> block()
    else -> timeout(limit, block)
}

// Stops a schedule instead of letting it sleep into a deadline the next attempt could not meet.
private fun <Output> Schedule<Throwable, Output>.within(budget: Budget): Schedule<Throwable, Output> {
    fun loop(input: Throwable, self: ScheduleStep<Throwable, Output>): Schedule.Decision<Throwable, Output> =
        when (val decision = self(input)) {
            is Continue ->
                if (decision.delay < budget.remaining()) {
                    Continue(decision.output, decision.delay) { loop(it, decision.step) }
                } else {
                    Done(decision.output)
                }

            is Done -> decision
        }

    return Schedule { loop(it, step) }
}
