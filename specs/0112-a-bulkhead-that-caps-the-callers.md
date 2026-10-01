# 0112 — A bulkhead that caps the callers

## Problem

Virtual threads make a request cheap, so nothing upstream limits how many handlers call one dependency at once. A
slow partner API with a pool of 20 connections gets 5,000 callers queued on it. Each waits out its own timeout while
holding the memory of its request, and other dependencies on the same node starve. Pekko gets bounded concurrency
from its dispatcher's size; resilience4j has a `Bulkhead`. A Lark service writes a `Semaphore` by hand, and so it
has no metric and no declared error.

## Not doing

- **A thread-pool bulkhead.** resilience4j's `ThreadPoolBulkhead` exists to cap platform threads. Here a thread is
  free, and the scarce thing is the dependency. A permit count is the whole bulkhead.
- **Per-key limits** (per tenant, per account). A map of bulkheads is the caller's choice of key.
- **Adaptive limits** (Netflix's `concurrency-limits`, Vegas, AIMD). The limit is a fixed number.
- **Stream or actor operators.** `Stream.mapPar` already bounds its own concurrency.

## Shape

```kotlin
val bulkhead = Bulkhead(name = "partner-api", maxConcurrent = 20, maxWait = 50.milliseconds)

val quote = bulkhead { http.quote(id) }                // throws Rejected.BulkheadFull after maxWait
val quote = (breaker then bulkhead) { http.quote(id) }

bulkhead.available // permits free now
```

- `Bulkhead` implements `Guard` (spec 0110).
- It is a **fair** `java.util.concurrent.Semaphore` with `maxConcurrent` permits. Callers are admitted in the order
  they arrived.
- `maxWait` defaults to `ZERO`, which means admit or reject at once. A positive wait is `tryAcquire(maxWait)`, which
  can be interrupted, so a `timingOut` outside the bulkhead ends it.
- The permit is released in a `finally`. A throw, a `raise` or an interrupt in the block never leaks one.
- Metrics: `lark.bulkhead.in_use` (gauge) and `lark.bulkhead.calls` (counter tagged `outcome=admitted|rejected`),
  both tagged with `name`.

## Why this shape

On virtual threads a semaphore is the bulkhead. Blocking in `tryAcquire` parks a virtual thread, which costs
nothing, so a separate queue or executor adds nothing. Fairness costs a little throughput. In exchange the
longest-waiting caller is never overtaken, which is what someone reading a p99 expects. The alternative,
`Semaphore(n, false)`, is faster but lets a late caller jump the queue. Inside the recommended order the bulkhead
sits inside the rate limiter, so a caller waiting for a token holds no permit.

## Depends on

0110, for `Guard` and `Rejected`. It is independent of 0111 and 0113.

## Stack

- [ ] **`spec-0112-bulkhead`** — `Bulkhead`, in `lark`.
      Done when: with `maxConcurrent = 2`, a third concurrent caller on a virtual thread is rejected at once when
      `maxWait = ZERO` and admitted when a permit frees within `maxWait`, and a block that throws, raises or is
      interrupted gives its permit back.
- [ ] **`spec-0112-bulkhead-metrics`** — the gauge and counter, plus a cookbook section "Cap the calls to something
      slow".
      Done when: `capturingMetrics` reads back `in_use` and each outcome by name.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should `maxWait` follow `clock.get()`?** `Semaphore.tryAcquire` waits on real time, so `TestClock` cannot move
   it. Recommend: no. Tests use `ZERO`, or a short real wait released by a latch.
2. **Fair or unfair by default?** Recommend fair, with no parameter until someone measures the difference.
3. **Should `maxConcurrent` change at runtime?** Recommend no. A different limit is a different `Bulkhead`.
