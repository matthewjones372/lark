# 0110 — Guards that compose

## Problem

Specs 0111–0113 add a circuit breaker, a rate limiter and a bulkhead, and Lark already has `retry` and `timeout`.
Nested as closures, no guard can see the others, so two costs follow. The order matters, but only a cookbook says
what it should be. Each guard also wastes what the others know: a retry sleeps on its own schedule into a limiter
that knew when the next token was due, or into a breaker that will not half-open before the caller gives up. A token
is spent on a call the bulkhead then refuses. resilience4j's `Decorators` has the same gap.

## Not doing

- **Branches.** No fallbacks and no hedged requests. A policy is a chain, not a graph. A fallback is the caller's
  `ifRejected`.
- **Reordering.** The compiler checks the order it is given and never rearranges it, so the code reads in the order
  it runs.
- **Policies over streams or actors.** A stage or an `ask` is a call inside a policy.

## Shape

```kotlin
val partner: Policy = policy("partner-api") {
    deadline(2.seconds)                                    // one budget for the call, retries included
    retry(Schedule.exponential<Throwable>(100.milliseconds).jittered() zipLeft Schedule.recurs(3))
    guard(breaker)                                         // 0111
    guard(limiter)                                         // 0113
    guard(bulkhead)                                        // 0112
    attemptTimeout(500.milliseconds)                       // each attempt
}

val quote = partner { http.quote(id) }                    // throws a Rejected, or the call's own throw
either { guarded(partner, ifRejected = { QuoteError.Unavailable(it) }) { quote(id) } }
```

- **A `Policy` is a value**: a name and a `List<Step>` of sealed nodes (`Deadline`, `Retry`, `Breaker`, `Limit`,
  `Bulkhead`, `AttemptTimeout`, `Custom(guard)`). It can be inspected in a test without running.
- **Checked when it is built.** `policy { }` throws `IllegalArgumentException` for an order outside
  deadline → retry → breaker → limiter → bulkhead → attemptTimeout, for a repeated step, and for an
  `attemptTimeout` longer than the deadline. The message names the step and the expected order. `Custom` steps may
  go anywhere and are not checked.
- **One `Rejected`**, sealed, with three cases: `CircuitOpen(retryAt)`, `RateLimited(retryAfter)` and
  `BulkheadFull`. `guarded` turns it into the caller's own error. A `raise` passes through every step untouched.
- **The runner reads the whole plan**, for every attempt:

| Event | What the runner does |
|---|---|
| Breaker open | If `retryAt` is within the deadline, wait for half-open, and the wait uses no attempt. Otherwise reject now. |
| No token yet | Reserve with `maxWait` capped at the remaining budget. If `retryAfter` exceeds that budget, reject now. |
| Bulkhead full | Refund the token, count a failed attempt, and continue on the retry schedule. |
| Call throws | The breaker records it, and retry follows its schedule. Stop early if the next delay would overrun the deadline. |
| Each attempt | One fork, for whichever is shorter: `attemptTimeout` or the remaining budget. |

- Metrics: `lark.policy.calls` (a counter tagged `policy`, `outcome=success|failure|rejected` and
  `refused_by=breaker|limiter|bulkhead|deadline`). Each guard keeps its own meters as well.

## Why this shape

A description compiled once is how Lark already treats streams and endpoints, and it is what lets the runner act on
what each guard knows. The wins are fewer wasted attempts, tokens and waits, not CPU: a guard costs nanoseconds next
to a network call. Checking the order rather than taking named fields keeps the code reading in the order it runs,
and lets a policy hold a `Custom` guard. The alternative, `policy(retry = …, breaker = …)`, cannot be written in a
wrong order, but it hides the order from the reader and has no place for a guard Lark does not know. The guards
themselves (0111–0113) expose only what the runner calls, such as `admit`, `record`, `reserve`, `refund` and
`acquire`, so all of the cross-guard behaviour lives in one file.

## Depends on

Nothing. 0111–0113 each depend on this one. Each guard's PR adds its `Step` and its rows of the runner.

## Stack

- [ ] **`spec-0110-policy`** — `Policy`, `Step`, the `policy { }` builder and its order check, `Rejected`,
      `guarded`, and a runner for `deadline`, `retry`, `attemptTimeout` and `Custom`, in `lark`.
      Done when: a policy built in the wrong order throws naming the expected order; on a `TestClock`, retry stops
      before a delay that would overrun the deadline, each attempt is cut to the remaining budget, and a raise
      passes through uncounted.
- [ ] **`spec-0110-policy-metrics`** — `lark.policy.calls`, plus a cookbook section "Guard a call to something
      unreliable".
      Done when: `capturingMetrics` reads back each outcome and `refused_by` by name.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Settled

1. **Does a wait for a token or for half-open use up a retry attempt?** No. The call was never made, and the
   deadline already bounds the wait.
2. **Where can a `Custom` step go?** Anywhere, unchecked. A guard Lark cannot see into cannot be ordered by it.
3. **Is a policy without `deadline` allowed?** Yes. The budget is then unbounded, and each guard's own `maxWait` is
   its only limit.
