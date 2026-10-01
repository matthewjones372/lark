# 0112 — A limiter that paces the calls

## Problem

A partner API that allows 50 requests a second answers 429 at the 51st, and some APIs ban a client that keeps
going. A Lark service can `retry` after the 429, but it cannot avoid sending the request in the first place. Pekko
paces with `Source.throttle` and resilience4j with `RateLimiter`. A Lark handler, which is plain blocking code on a
virtual thread, has nothing to use and writes its own token bucket.

## Not doing

- **Distributed limits.** Each process has its own bucket. A quota shared across nodes needs Redis or the cluster
  and is not a core concern. A per-node share is `rate / nodes`, set by the caller.
- **Per-key limits** (per tenant). As in 0111, a map of limiters is the caller's choice of key.
- **A stream `throttle`.** `lark-stream` can call `throttle` from a `map`. A native operator is a stream spec.
- **Server-side admission** (answering 429 to our own callers). That belongs to the HTTP layer, built on this.

## Shape

```kotlin
val partner = RateLimiter(name = "partner-api", rate = 50, per = 1.seconds, burst = 10, maxWait = 200.milliseconds)

val quote = partner.throttle { http.quote(id) }        // waits up to maxWait, else throws RateLimitedException(retryAfter)

either<QuoteError, Quote> {
    partner.throttle(ifLimited = { QuoteError.Busy(it.retryAfter) }) { quote(id) }
}

partner.tryAcquire()        // a token now, or false; never waits
```

- A **token bucket**. It refills at `rate / per` and holds at most `burst` tokens. The state is one
  `AtomicReference<Bucket(tokens, at: Instant)>`, refilled lazily from `clock.get().now()` on each call. There is
  no timer thread.
- **Reservation**: a caller takes a token even when the count goes negative, then sleeps until that token would
  have arrived. This is Guava's `RateLimiter` model. Callers are served in the order they reserved, and nobody has
  to poll. A reservation that would wait longer than `maxWait` is not taken: the caller is rejected at once, told
  `retryAfter`, and the bucket is unchanged.
- The sleep is `clock.get().sleep`, so `TestClock` drives pacing in tests and an interrupt ends the wait.
- Metrics: `lark.ratelimiter.calls` (counter tagged `outcome=immediate|waited|rejected`) and
  `lark.ratelimiter.wait` (histogram, in milliseconds), both tagged with `name`.

## Why this shape

A token bucket with burst is what API quotas are written in. Reserving then sleeping uses one compare-and-set per
call and a parked virtual thread, with no queue to manage. The alternative is resilience4j's fixed refresh period:
it is simpler, but it lets `2 × rate` through across a period boundary, and that is what gets a client banned.
Rejecting at once when the wait would exceed `maxWait` keeps a caller from waiting out its whole budget only to
time out anyway. Inside `Raise` a limited call becomes the caller's error through `ifLimited`, as in 0110 and 0111.

These compose outside-in as `breaker.protect { bulkhead.limit { limiter.throttle { call() } } }`: an open breaker
spends no permit and no token.

## Depends on

Nothing. It is independent of 0110 and 0111.

## Stack

- [ ] **`spec-0112-ratelimiter`** — `RateLimiter`, `throttle`, `tryAcquire`, `RateLimitedException` and
      `Raise<E>.throttle`, in `lark`.
      Done when: on a `TestClock`, `burst` calls pass at once, the next waits exactly `per / rate`, a wait past
      `maxWait` is rejected with the right `retryAfter` and leaves the bucket unchanged, and concurrent callers are
      served in reservation order.
- [ ] **`spec-0112-ratelimiter-metrics`** — the counter and histogram, plus a cookbook section "Stay under someone
      else's quota".
      Done when: `capturingMetrics` reads back each outcome and the waits by name.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Reserve-and-sleep or poll?** Recommend reserve. Polling wakes every waiter on every refill.
2. **Is `burst` its own parameter or always equal to `rate`?** Recommend its own, defaulting to `rate`. Quotas
   often allow a smaller burst than their per-second rate.
3. **Should one call be able to take more than one token** (`throttle(cost = 5)`, for weighted endpoints)?
   Recommend yes, defaulting to 1. It is one parameter, and adding it later would change a signature.
4. **Should a 429 from the server feed back into the bucket?** Recommend no. That is `retry` with a `Schedule` that
   reads `Retry-After`, in the caller's own code.
