# 0111 — A bulkhead that caps the callers

## Problem

Virtual threads make a request cheap, so nothing upstream limits how many handlers call one dependency at once. A
slow partner API with a pool of 20 connections gets 5,000 callers queued on it. Each waits out its own timeout while
holding the memory of its request, and other dependencies on the same node starve. Pekko gets bounded concurrency
from its dispatcher's size; resilience4j has a `Bulkhead`. A Lark service writes a `Semaphore` by hand, and so it
has no metric and no declared error.

## Not doing

- **A thread-pool bulkhead.** resilience4j's `ThreadPoolBulkhead` exists to cap platform threads. Here a thread is
  free, and the scarce thing is the dependency. A permit count is the whole bulkhead.
- **Per-key limits** (per tenant, per account). A map of bulkheads is the caller's choice of key. That can be a
  later spec if it shows up twice.
- **Adaptive limits** (Netflix's `concurrency-limits`, Vegas, AIMD). The limit is a fixed number.
- **Stream or actor operators.** `Stream.mapPar` already bounds its own concurrency.

## Shape

```kotlin
val partner = Bulkhead(name = "partner-api", maxConcurrent = 20, maxWait = 50.milliseconds)

val quote = partner.limit { http.quote(id) }           // throws BulkheadFullException after maxWait

either<QuoteError, Quote> {
    partner.limit(ifFull = { QuoteError.Busy }) { quote(id) }   // full is a raise, not a throw
}

partner.available // permits free now
```

- A **fair** `java.util.concurrent.Semaphore` with `maxConcurrent` permits. Callers are admitted in the order they
  arrived.
- `maxWait` defaults to `ZERO`, which means admit or reject at once. A positive wait is `tryAcquire(maxWait)`, which
  can be interrupted, so a `timeout` around `limit` ends it.
- The permit is released in a `finally`. A throw, a `raise` or an interrupt in the block never leaks one.
- Metrics: `lark.bulkhead.in_use` (gauge) and `lark.bulkhead.calls` (counter tagged `outcome=admitted|rejected`),
  both tagged with `name`.

## Why this shape

On virtual threads a semaphore is the bulkhead. Blocking in `tryAcquire` parks a virtual thread, which costs
nothing, so a separate queue or executor adds nothing. Fairness costs a little throughput. In exchange the
longest-waiting caller is never overtaken, which is what someone reading a p99 expects. The alternative,
`Semaphore(n, false)`, is faster but lets a late caller jump the queue. A full bulkhead is something a caller
should plan for, so inside `Raise` it becomes the caller's error through `ifFull`, as the breaker in spec 0110
handles `ifOpen`. Outside `Raise` it throws.

## Depends on

Nothing. It is independent of 0110 and composes with it:
`breaker.protect { partner.limit { … } }`.

## Stack

- [ ] **`spec-0111-bulkhead`** — `Bulkhead`, the throwing `limit`, `BulkheadFullException` and `Raise<E>.limit`, in
      `lark`.
      Done when: with `maxConcurrent = 2`, a third concurrent caller on a virtual thread is rejected at once when
      `maxWait = ZERO` and admitted when a permit frees within `maxWait`, and a block that throws, raises or is
      interrupted gives its permit back.
- [ ] **`spec-0111-bulkhead-metrics`** — the gauge and counter, plus a cookbook section "Cap the calls to something
      slow".
      Done when: `capturingMetrics` reads back `in_use` and each outcome by name.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should `maxWait` follow `clock.get()`?** `Semaphore.tryAcquire` waits on real time, so `TestClock` cannot move
   it. Recommend: no. Tests use `ZERO`, or a short real wait released by a latch. Putting a semaphore on the clock
   is a lot of code for a wait measured in milliseconds.
2. **Fair or unfair by default?** Recommend fair, with no parameter until someone measures the difference.
3. **Should `maxConcurrent` change at runtime?** Recommend no. A different limit is a different `Bulkhead`.
