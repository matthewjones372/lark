# 0113 — A limiter that paces the calls

## Problem

A partner API that allows 50 requests a second answers 429 at the 51st, and some APIs ban a client that keeps
going. A Lark service can `retry` after the 429, but it cannot avoid sending the request in the first place. Pekko
paces with `Source.throttle` and resilience4j with `RateLimiter`. A Lark handler, which is plain blocking code on a
virtual thread, has nothing to use and writes its own token bucket.

## Not doing

- **Distributed limits.** Each process has its own bucket. A per-node share is `rate / nodes`, set by the caller.
- **Per-key limits** (per tenant). As in 0112, a map of limiters is the caller's choice of key.
- **A stream `throttle`.** `lark-stream` can call a limiter from a `map`. A native operator is a stream spec.
- **Server-side admission** (answering 429 to our own callers). That belongs to the HTTP layer, built on this.

## Shape

```kotlin
val limiter = RateLimiter(name = "partner-api", rate = 50, per = 1.seconds, burst = 10, maxWait = 200.milliseconds)

val quote = limiter { http.quote(id) }                 // waits up to maxWait, else throws Rejected.RateLimited
val quote = (breaker then limiter then bulkhead) { http.quote(id) }

limiter.tryAcquire()        // a token now, or false; never waits
```

- `RateLimiter` implements `Guard` (spec 0110). When it refuses it throws `Rejected.RateLimited(name, retryAfter)`.
- A **token bucket**. It refills at `rate / per` and holds at most `burst` tokens. The state is one
  `AtomicReference<Bucket(tokens, at: Instant)>`, refilled lazily from `clock.get().now()` on each call. There is
  no timer thread.
- **Reservation**: a caller takes a token even when the count goes negative, then sleeps until that token would
  have arrived. This is Guava's `RateLimiter` model. Callers are served in the order they reserved, and nobody has
  to poll. A reservation that would wait longer than `maxWait` is not taken: the caller is rejected at once with its
  `retryAfter`, and the bucket is unchanged.
- The sleep is `clock.get().sleep`, so `TestClock` drives pacing in tests and an interrupt ends the wait.
- Metrics: `lark.ratelimiter.calls` (counter tagged `outcome=immediate|waited|rejected`) and
  `lark.ratelimiter.wait` (histogram, in milliseconds), both tagged with `name`.

## Why this shape

A token bucket with burst is what API quotas are written in. Reserving then sleeping uses one compare-and-set per
call and a parked virtual thread, with no queue to manage. The alternative is resilience4j's fixed refresh period:
it is simpler, but it lets `2 × rate` through across a period boundary, and that is what gets a client banned.
Rejecting at once when the wait would exceed `maxWait` keeps a caller from waiting out its whole budget only to time
out anyway. Inside an open breaker no token is spent, because the breaker sits outside the limiter in the
recommended order.

## Depends on

0110, for `Guard` and `Rejected`. It is independent of 0111 and 0112.

## Stack

- [ ] **`spec-0113-ratelimiter`** — `RateLimiter` and `tryAcquire`, in `lark`.
      Done when: on a `TestClock`, `burst` calls pass at once, the next waits exactly `per / rate`, a wait past
      `maxWait` is rejected with the right `retryAfter` and leaves the bucket unchanged, and concurrent callers are
      served in reservation order.
- [ ] **`spec-0113-ratelimiter-metrics`** — the counter and histogram, plus a cookbook section "Stay under someone
      else's quota".
      Done when: `capturingMetrics` reads back each outcome and the waits by name.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Reserve-and-sleep or poll?** Recommend reserve. Polling wakes every waiter on every refill.
2. **Is `burst` its own parameter or always equal to `rate`?** Recommend its own, defaulting to `rate`.
3. **Should one call be able to take more than one token** (for weighted endpoints)? Recommend yes, as a
   `limiter.costing(5)` guard, so the call shape stays `Guard`'s.
4. **Should a 429 from the server feed back into the bucket?** Recommend no. That is `retrying` with a `Schedule`
   that reads `Retry-After`.
