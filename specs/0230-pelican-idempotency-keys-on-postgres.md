# 0230 — Pelican's idempotency keys on Postgres

## Problem

Pelican's spec 0092 gives an endpoint `idempotencyKey()` and an `Api` `idempotency(store, ttl)`. Pelican answers the
409 and 422 and replays the first response itself, but holds no keys: its `IdempotencyStore` is an interface, and
the only implementation it ships, `InMemoryIdempotencyStore`, shares nothing with another node. A service with
several instances needs the store spec 0226 built, `PostgresIdempotency`, behind Pelican's interface.

## Not doing

- No rate-limit adapter: Pelican has no `RateLimiter` interface yet, so `surefold-pelican-ratelimit` still waits.
  Nor `SessionResolver` or `TenantResolver`, which Pelican has not added either.
- No change to Pelican: its interface is used as it is.
- No second table: the adapter keeps Pelican's keys in `surefold_idempotency`, beside any the service keeps itself.

## Shape

```kotlin
val api = api {
    idempotency(PelicanIdempotencyStore(PostgresIdempotency(dataSource)), ttl = 24.hours)
    // endpoints declared with idempotencyKey()
}
```

- **One module**, `data/surefold-pelican-idempotency`, on `surefold-idempotency` and `pelican-core`.
- **The scope**: Pelican's `IdempotencyScope(operation, subject, key)` becomes `IdempotencyKey(scope, key)` with
  `scope = operation + U+001F + subject`, so one caller's key never answers another's, nor another operation's.
- **Tokens**: Pelican's `complete` and `release` take no token, so the adapter keeps the token `reserve` answered,
  per scope, on the node that reserved it. Pelican completes or releases on that node, after the handler it ran. A
  node that dies holding a token leaves the reservation to its lease, as spec 0226's store already does.
- **Lease and ttl**: Pelican passes the lease to `reserve` and the ttl to `complete`. `PostgresIdempotency` gains a
  `reserve` that takes the lease per call, a `complete` that sets the expiry from a ttl, and a `lookup` that answers
  what a key holds without taking it. These are additions; its existing calls are unchanged.
- **Responses**: Pelican's `StoredResponse` has a media type that Surefold's has not. The adapter keeps it as a
  `:media-type` pseudo-header, which no real header can collide with, and gives it back on the way out.

## Why this shape

A token map in the adapter keeps Surefold's store as it is: its tokens are what stop a holder whose lease was taken
over from writing over the new holder's answer, and Pelican's interface has nowhere to carry one. **Alternative:**
complete by scope alone, last writer wins; recommended against, since it reopens the takeover race spec 0226 closed.

## Stack

- [ ] **`spec-0230-adapter`** — the store additions, `PelicanIdempotencyStore`, its module, and docs.
      Done when: Pelican's own idempotency tests' cases hold against the adapter on Postgres: fifty reservations of
      one key at once give one `Reserved`; a completed key replays to every node; a released key runs again; a
      different body is a `Mismatch`; and one subject's key is not another's.

## Acceptance

```bash
./gradlew :surefold-pelican-idempotency:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to merge the Pelican stack and implement;
revisit in review).

1. **Which Pelican release?** The interface is on Pelican's `main`, unreleased; Central's latest is 1.1.0.
   Recommended: depend on the release that carries spec 0092, 1.2.0, and merge this once it is out.
2. **Where the module lives.** Recommended: `data/`, beside the store it adapts, as `surefold-jobs-tenancy` does.
