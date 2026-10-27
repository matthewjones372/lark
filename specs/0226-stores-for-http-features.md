# 0226 — Stores for idempotent requests and rate limits

## Problem

A booking API must not take a payment twice when a client retries a timed-out `POST`, and must not let one client
exhaust a provider quota shared by every instance. Pelican is adding `IdempotencyStore` and `RateLimiter` interfaces
for its HTTP side; neither is released yet, and neither says where the state lives. With several instances behind a
load balancer it has to live somewhere they share, and reserving a key has to be atomic, or two instances that
receive the same key at once both run the handler.

## Not doing

- No HTTP: reading the `Idempotency-Key` header, fingerprinting a request and answering 409 or 429 are Pelican's.
- No Pelican adapters yet: `surefold-pelican-idempotency` and `surefold-pelican-ratelimit` wait for Pelican to
  release the interfaces they implement. The stores here are shaped so each adapter is a few lines.
- No Redis: Postgres, which the service already runs, and memory for tests and single instances.

## Shape

```kotlin
val store = PostgresIdempotency(dataSource)
when (val reservation = store.reserve(IdempotencyKey(scope = tenantId, key = header), fingerprint, ttl = 24.hours)) {
    Reservation.Reserved -> runHandler().also { store.complete(key, StoredResponse(201, headers, body)) }
    is Reservation.Replay -> reservation.response          // the first run's answer, again
    Reservation.InProgress -> conflict()                   // another instance is running it now
    Reservation.Mismatch -> unprocessable()                // same key, different request
}
jobWorker(handlers + IdempotencyPrune.handledBy(store), cron = listOf(IdempotencyPrune.every(Cron.parse("0 * * * *", UTC))))

val limits: RateLimits = PostgresRateLimits(dataSource)    // or InMemoryRateLimits()
when (val decision = limits.take("checkout:$clientId", TokenBucket(10, per = 1.seconds, burst = 20))) {
    is Decision.Allowed -> …
    is Decision.Denied -> tooManyRequests(retryAfter = decision.retryAfter)
}
```

- **Reserving** is one `insert … on conflict do update … where` that only an expired key, or a reservation whose
  lease ran out because its instance died, lets through; so of two instances, exactly one is `Reserved`. A
  reservation holds a lease (default 1 minute); `release` frees it when the handler fails, so a retry can run.
- **Responses** are kept as status, headers and body bytes, until the key's `ttl`. A prune deletes expired keys, run
  as a `surefold-jobs` cron entry.
- **A token bucket** per key: Postgres refills it from the clock and takes from it in one statement on one row, so
  every instance draws on the same bucket; `Denied` says when the next token arrives.

## Why this shape

Two modules, so a service that wants rate limits does not take a jobs dependency. `surefold-idempotency` depends on
`surefold-jobs` for its prune, as the brief asks; **alternative:** a `prune()` the service schedules itself;
recommended against, since a store nobody prunes grows without end.

## Stack

- [ ] **`spec-0226-idempotency`** — `surefold-idempotency`: the store, its changelog, reserve, complete, release.
      Done when: 50 threads reserving one key at once get exactly one `Reserved`, a replay answers the stored
      response, a different fingerprint is a mismatch, and a lease left by a dead instance is taken over.
- [ ] **`spec-0226-prune`** — `prune`, `IdempotencyPrune` and its cron entry.
      Done when: a worker with the entry deletes expired keys and keeps live ones.
- [ ] **`spec-0226-ratelimit`** — `surefold-ratelimit`: `RateLimits`, in memory and on Postgres.
      Done when: 8 threads taking 1,000 times from one bucket of 10 a second, burst 20, over 5 seconds of a
      `TestClock`, are allowed exactly 70, on both.
- [ ] **`spec-0226-docs`** — `docs/http-stores.md`, the module tables.
      Done when: the guide's example is held to a compiled source.

## Acceptance

```bash
./gradlew :surefold-idempotency:build :surefold-ratelimit:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to keep going overnight; revisit in review).

1. **What scopes a key?** Recommended: a `scope` the caller chooses, usually tenant or client id, so two tenants'
   identical keys never meet.
2. **Store headers how, with no JSON library?** Recommended: two `text[]` columns, names and values, in order.
3. **The adapters' names:** Recommended: `surefold-pelican-idempotency` and `surefold-pelican-ratelimit`, as the brief
   suggests, in `integrations/`.
