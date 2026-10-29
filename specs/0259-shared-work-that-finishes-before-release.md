# 0259 — Shared work that finishes before release

Approved by the owner on 2026-10-29: recommendations 1–4 and the named stack agreed; release approval remains separate.

## Problem

An owner must finish shared lookups and cleanup before releasing their dependencies.
`Cache.close` cancels sleepers then `SingleFlight.cancelAll`; neither joins workers.
`SingleFlight` starts its leader independently through `surefoldFuture`, so ending
one waiter cannot establish that lifetime boundary. An interrupt is not completion.
Checked v0.16.0 (`6db3daad`) and main `40671b12`: these runtime sources match.
[879](https://github.com/matthewjones372/surefold/pull/879) isolates waiter cancellation;
[891](https://github.com/matthewjones372/surefold/pull/891) adds interrupt-only cancellation;
[905](https://github.com/matthewjones372/surefold/pull/905) fixes worker registration versus cancellation.

## Not doing

Application resource graphs, changing waiter cancellation, a new executor/framework,
new dependencies, replacing reusable cancelAll, or retention policy ([0258](0258-cache-results-without-retention-or-expiry.md)).

## Shape

Proposed additive signatures: `SingleFlight.closeAndJoin(): Unit` and
`Cache.closeAndJoin(): Unit`. An existing Resource release can call the latter;
no resource wrapper or constructor change is required. Decisions below remain open.

- Owner release closes admission atomically with worker registration: every accepted
  lookup/explicit refresh/sleeper is owned before it can start, or is refused.
  A post-close run/get/refresh fails with IllegalStateException, as Cache.get does today.
- Cancel all accepted work, then await actual worker termination, finally blocks,
  terminal answer publication and cache-owned layer/onEvict/schedule callbacks.
  Promise completion or removal from the flight map alone is not proof of termination.
- Cover startup before registration, same-key replacement, refresh spawning another
  lookup and concurrent invalidation/callback work; no accepted work escapes release.
- Mark closing before cancellation. Late results may answer existing waiters but
  cannot be retained, written to layers or schedule new work. Preserve invalidation
  generation checks; no worker or owned callback runs after awaited release returns.
  Invalidation is refused once awaited close begins; stats remain readable.
- Concurrent/repeated owner releases await the same completion. A prior Cache.close
  can be followed by closeAndJoin. cancelAll remains reusable and interrupt-only;
  cancelling one waiter still neither cancels the leader nor changes other answers.
- Keep existing Cache.close/AutoCloseable callers source/binary compatible and
  interrupt-only; adoption of the awaited path is explicit. Finite cache behaviour,
  lookup failures, first-caller bindings and KeyCache.run remain covered by regression tests.

## Why this shape

Additive awaited release preserves close timing. An owned Resource factory makes
allocation explicit but adds a path; strengthening close is smaller but changes callers.

## Stack

Owner approved the four-entry delivery split on 2026-10-29; public contracts and release gating are unchanged.
- [x] **`codex/0259-flight-release`** — closed admission, cancellation and joined SingleFlight workers ([925](https://github.com/matthewjones372/surefold/pull/925)).
      Done when: startup/cancel races, waiter isolation and reusable cancelAll are proven.
- [ ] **`codex/0259-cache-workers`** — privately own background startup, bindings and displaced sleepers.
      Done when: accepted work is owned before submission and ordinary close remains interrupt-only.
- [ ] **`codex/0259-cache-release`** — join every cache worker and prevent late publication.
      Done when: explicit/ahead refresh, layer/callback cleanup and dependency order are proven.
- [ ] **`codex/0259-release-guide`** — resource example and existing-caller compatibility coverage.
      Done when: release interruption/self-close decisions and both close paths are documented.

## Acceptance

Tests first: multiple keys/waiters, late registration, blocked and cancellation-resistant
lookup/finally, worker versus cleanup failure reporting, zero-delay refresh, invalidation races,
repeated/concurrent/interrupted/self close and dependency release ordering.
Use existing Promise/Shared/Resource/scoped work/TestClock/eventually, no new dependency.
```bash
./gradlew :surefold:test :surefold-cache:test :surefold-tenancy-crypto:test
./gradlew :surefold-app:test --tests '*SpecsSuite'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. Add closeAndJoin, add an owned Resource factory, or strengthen close? Recommend
   additive closeAndJoin on both existing types; keep close/cancelAll compatibility.
2. Interrupted release: return early, impose a timeout, or finish joining? Recommend
   finish joining and restore interruption; resistant cleanup can delay it indefinitely.
3. Self-close from owned lookup/cleanup/callback: partial close or reject? Recommend
   IllegalStateException before changing state; an external owner performs full release.
4. Keep or clear retained entries; re-report worker outcomes, fail fast, or aggregate only
   new close/cleanup defects? Recommend clear memory after joining: onEvict once per
   removed entry (K, V), without object-identity deduplication; shared layers stay intact.
   Finish all cleanup and aggregate new cleanup defects with suppressed failures; worker
   outcomes already delivered to waiters are not reported again by closeAndJoin.
