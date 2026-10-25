# 0171 — A limit nobody has to pick

## Problem

Backpressure in Lark today means choosing a number: `mapPar(64)`, `Bulkhead(name, 100)`, a `Semaphore`. Reactor
and Pekko ask for the same number (`flatMap(f, 256)`, `mapAsync(16)`). The right number depends on a downstream
whose capacity nobody knows, and which changes under load, during an incident or after a deploy. Set it too low
and the service is slow while the dependency sits idle. Set it too high and the dependency drowns, latency climbs,
and timeouts retry into the same queue.

Separately, core `parMap(items) { }` has no limit at all. In `blocking-vs-reactive`, the same unbounded shape
(`vt-naive`) let 19,989 elements pile up where every bounded contestant held 17.

No Reactor or Pekko operator adjusts its own concurrency. A limit that finds the right number by itself would be
something Lark offers that they do not.

## Not doing

- No rate limiting (calls per second). `RateLimiter` already exists.
- No load shedding at the edge (rejecting requests at the HTTP server).
- No distributed limit: each limit is per JVM.
- No change to the default of an existing operator. Opting in is explicit.

## Shape

One value in core, `Limit`, accepted wherever a concurrency number is today:

```kotlin
val orders = Limit.adaptive(name = "orders-db", initial = 20, max = 1_000)

// in a stream
records.mapPar(orders) { record -> ordersDb.write(record) }

// in plain code
parMap(ids, limit = orders) { id -> ordersDb.find(id) }

// in a policy
val guarded = policy("orders-db") { guard(orders) }
```

- `Limit.fixed(n)` is today's behaviour. Each existing `Int` overload delegates to it.
- `Limit.adaptive` adjusts in-flight work from observed latency, the way TCP Vegas and Netflix's
  `concurrency-limits` do. While latency stays near the lowest seen, the limit grows. When latency rises, the
  dependency is queueing, so the limit shrinks. A call that failed or timed out counts as a rise.
- A shared `Limit` (one per dependency) bounds every caller of that dependency together: a stream, a `parMap` and
  a request handler all draw from one budget.
- The current limit and in-flight count are published through Lark's metrics, as `Bulkhead` publishes
  `lark.bulkhead.in_use`.

## Why this shape

Option A, recommended: an adaptive `Limit` value that operators accept. It is opt-in and has one algorithm in core.
It also composes, because one value can guard a dependency across streams and plain code.

Option B: make `parMap` and fork scopes bounded by default (say `4 × cores`), so the naive code is safe. That is
simpler and fixes `vt-naive`, but it changes behaviour under existing code, and a constant default is still a
number somebody picked.

A then B: B can follow as its own spec, with a default of `Limit.adaptive`.

## Stack

- [x] **`limit-value`** ([#486](https://github.com/matthewjones372/lark/pull/486)) — `Limit`, `Limit.fixed`, and the `Int` overloads delegating to it; no behaviour change.
      Done when: the existing tests pass unchanged.
- [x] **`limit-adaptive`** ([#486](https://github.com/matthewjones372/lark/pull/486)) — the adaptive algorithm, driven by an injected clock and fake latencies in tests.
      Done when: tests show the limit converging on a simulated dependency's capacity, and then following it down
      when that capacity halves.
- [x] **`limit-in-streams`** ([#487](https://github.com/matthewjones372/lark/pull/487)) — `mapPar`, `mapAsync` and 0170's unordered operators accept a `Limit`; Forks
      resizes its window live.
      Done when: parity is green with `Limit.fixed`, and a Forks test shows the window resizing.
- [ ] **`limit-in-core`** — `parMap(…, limit)` and `bulkhead(limit)`, with metrics.
      Done when: the core tests pass and a `TestMeterRegistry` assertion reads the published limit.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
# in blocking-vs-reactive: a new `overload` scenario whose downstream capacity drops by 5× halfway through
scripts/run-io.sh
```

The `overload` scenario compares `Limit.adaptive` with Reactor and Pekko at their best fixed concurrency for the
first half. "Lark wins" is defined in advance: equal throughput, and lower p99 latency after the drop.

## Open questions

- **Which algorithm?** Recommended: start with a gradient (Vegas-like) algorithm, which shrinks on latency before
  errors appear, and keep AIMD as a fallback. The choice is internal and can change later.
- **Is Option B wanted at all?** Recommended: defer to a later spec once A has run in a real service.
- ~~**Should a limit wait or reject when it is full?**~~ Answered (maintainer, 2026-10-25): wait in streams and in
  `parMap`, which is the backpressure. In a `Policy`, `guard(limit, maxWait)` follows `Bulkhead`'s `maxWait`, and a
  limit takes the bulkhead's place in the order.
