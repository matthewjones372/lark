package io.github.matthewjones372.lark

import arrow.core.NonFatal
import arrow.core.raise.Raise
import io.github.matthewjones372.lark.CircuitBreaker.Admission.Admitted
import io.github.matthewjones372.lark.CircuitBreaker.Admission.Refused
import io.github.matthewjones372.lark.Policy.Step
import io.github.matthewjones372.lark.Schedule.Decision.Continue
import io.github.matthewjones372.lark.Schedule.Decision.Done
import java.time.Instant
import java.util.concurrent.CancellationException
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

        data class Breaker(val breaker: CircuitBreaker) : Step

        data class Limit(val limiter: RateLimiter, val cost: Int) : Step

        data class Bulkhead(val bulkhead: io.github.matthewjones372.lark.Bulkhead) : Step

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

    fun guard(breaker: CircuitBreaker) {
        steps += Step.Breaker(breaker)
    }

    fun guard(limiter: RateLimiter, cost: Int = 1) {
        require(cost >= 1) { "A call takes at least one token, not $cost." }
        steps += Step.Limit(limiter, cost)
    }

    fun guard(bulkhead: Bulkhead) {
        steps += Step.Bulkhead(bulkhead)
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
    BREAKER("breaker"),
    LIMIT("limiter"),
    BULKHEAD("bulkhead"),
    ATTEMPT_TIMEOUT("attemptTimeout"),
}

private val Step.kind: Kind?
    get() = when (this) {
        is Step.Deadline -> Kind.DEADLINE
        is Step.Retry -> Kind.RETRY
        is Step.Breaker -> Kind.BREAKER
        is Step.Limit -> Kind.LIMIT
        is Step.Bulkhead -> Kind.BULKHEAD
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

// A raise is the call's own answer and passes through uncounted, as it passes through every step.
private fun <E, A> Raise<E>.runPolicy(policy: Policy, block: Raise<E>.() -> A): A {
    var outcome: Pair<String, String>? = "failure" to "none"
    try {
        return from(policy, 0, Budget(null), block).also { outcome = "success" to "none" }
    } catch (rejected: Rejected) {
        outcome = "rejected" to rejected.refusedBy
        throw rejected
    } catch (passed: DeadlinePassed) {
        outcome = "rejected" to "deadline"
        throw passed
    } catch (raised: CancellationException) {
        outcome = null
        throw raised
    } finally {
        outcome?.let { (result, by) ->
            counter("lark.policy.calls", "policy" to policy.name, "outcome" to result, "refused_by" to by).increment()
        }
    }
}

private val Rejected.refusedBy: String
    get() = when (this) {
        is Rejected.CircuitOpen -> "breaker"
        is Rejected.RateLimited -> "limiter"
        is Rejected.BulkheadFull -> "bulkhead"
    }

/** The policy's deadline ran out, as against a timeout of the call's own or one attempt's. */
private class DeadlinePassed : TimeoutException("the policy's deadline has passed")

private fun <E, A> Raise<E>.from(policy: Policy, at: Int, budget: Budget, block: Raise<E>.() -> A): A {
    if (at == policy.steps.size) {
        return if (policy.cutsBeforeTheCall) cut(budget.remaining(), budget, block) else block()
    }
    val next: Raise<E>.() -> A = { from(policy, at + 1, budget, block) }
    return when (val step = policy.steps[at]) {
        is Step.Deadline -> {
            val until = clock.get().now().plusNanos(step.total.inWholeNanoseconds)
            from(policy, at + 1, Budget(until), block)
        }

        is Step.Retry -> step.schedule.within(budget).retry { next() }

        is Step.Breaker -> through(step.breaker, budget, next)

        is Step.Limit -> paced(step.limiter, step.cost, budget, next)

        is Step.Bulkhead -> inside(step.bulkhead, budget, next)

        is Step.AttemptTimeout -> cut(minOf(step.each, budget.remaining()), budget, next)

        is Step.Custom -> with(step.guard) { guard(next) }
    }
}

private fun <E, A> Raise<E>.through(breaker: CircuitBreaker, budget: Budget, block: Raise<E>.() -> A): A {
    val admitted = admitted(breaker, budget)
    var result = CircuitBreaker.Result.UNCOUNTED
    try {
        return block().also { result = CircuitBreaker.Result.SUCCESS }
    } catch (thrown: Throwable) {
        // NonFatal is false for a raise and an interrupt as well as for what the JVM cannot recover from.
        if (NonFatal(thrown) && breaker.countsAsFailure(thrown)) result = CircuitBreaker.Result.FAILURE
        throw thrown
    } finally {
        breaker.record(admitted, result)
    }
}

// A refusal is waited out only when the policy has a deadline the wait fits inside. Without one it is refused at
// once, which is what a breaker is for.
private tailrec fun admitted(breaker: CircuitBreaker, budget: Budget): Admitted =
    when (val admission = breaker.admit()) {
        is Admitted -> admission

        is Refused -> {
            val wait = java.time.Duration.between(clock.get().now(), admission.retryAt).toKotlinDuration()
            val left = budget.remaining()
            if (wait <= ZERO || left.isInfinite() || wait >= left) {
                throw breaker.refused(admission.retryAt)
            }
            wait.sleepOff()
            admitted(breaker, budget)
        }
    }

// A token is waited for only when the wait fits the deadline. A call the bulkhead then refuses, or a wait an interrupt
// ends, was never made, so its tokens go back.
private fun <E, A> Raise<E>.paced(limiter: RateLimiter, cost: Int, budget: Budget, block: Raise<E>.() -> A): A {
    when (val reservation = limiter.reserve(cost, minOf(limiter.maxWait, budget.remaining()))) {
        is RateLimiter.Reservation.Refused -> throw Rejected.RateLimited(limiter.name, reservation.retryAfter)

        is RateLimiter.Reservation.Granted ->
            try {
                reservation.wait.sleepOff()
            } catch (interrupted: InterruptedException) {
                limiter.refund(cost)
                throw interrupted
            }
    }
    try {
        return block()
    } catch (full: Rejected.BulkheadFull) {
        limiter.refund(cost)
        throw full
    }
}

// The bulkhead's own wait, cut to what the deadline leaves. A refusal is a failed attempt to a retry outside it.
private fun <E, A> Raise<E>.inside(bulkhead: Bulkhead, budget: Budget, block: Raise<E>.() -> A): A {
    if (!bulkhead.acquire(minOf(bulkhead.maxWait, budget.remaining()))) throw Rejected.BulkheadFull(bulkhead.name)
    try {
        return block()
    } finally {
        bulkhead.release()
    }
}

// One fork for an attempt, and none at all when nothing bounds it. A timeout that leaves no budget is the
// deadline's, whichever limit was the shorter.
private fun <E, A> Raise<E>.cut(limit: Duration, budget: Budget, block: Raise<E>.() -> A): A = when {
    limit <= ZERO -> throw DeadlinePassed()

    limit.isInfinite() -> block()

    else ->
        try {
            timeout(limit, block)
        } catch (timedOut: TimeoutException) {
            throw if (budget.remaining() <= ZERO) DeadlinePassed().apply { initCause(timedOut) } else timedOut
        }
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
