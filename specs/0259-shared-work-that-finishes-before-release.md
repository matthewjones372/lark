# 0259 — Shared work that finishes before release

Approved by the owner on 2026-10-29: recommendations 1–4 and the named stack agreed; release approval remains separate.
The owner also approved the publication-ordering contract and five-entry stack below on 2026-10-29.

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
no resource wrapper or constructor change is required. Approved decisions are recorded below.

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

## Publication ordering (owner-approved amendment)

Checked v0.16.0 (`6db3daad`) and main `858605ab`: a generation check followed by a
separate memory/layer write can restore obsolete data after invalidation. A layer
read during removal can also readmit old data. Existing race regressions block before
the final generation check. A global FIFO would block unrelated keys and deadlock
existing completing reentry. An opaque layer can invalidate then finish its outer
write afterward; waiting for that write deadlocks. The owner approved these rules:

- Layer operations must not invoke, or synchronously await work invoking,
  get/refresh/invalidate on their owning Cache. Direct calls and inherited Surefold
  ownership context fail with IllegalStateException before admission/mutation.
  Arbitrary foreign-executor cycles cannot be detected. Other caches and stats remain
  callable; existing close stays interrupt-only and awaited self-close stays refused.
  This intentionally restricts existing reentrant layers; signatures remain unchanged.
- Preserve existing completing eviction/TTL/schedule reentry, including acyclic get
  of another key, without promising support for already-deadlocking cycles. Run TTL
  and schedule decisions outside conflict reservations, then conditionally admit their
  effects. Release memory-mutation reservations before eviction notifications, while
  retaining callback lifetime ownership through completion.
- Reserve actual key conflicts with an All barrier: unrelated B can progress while A
  layer work blocks, except where All requires a common boundary. Keep global generation
  suppression for publication not yet admitted, including unrelated keys. Earlier admitted
  unrelated phases finish independently; Key removal waits for earlier conflicting phases.
- Invalidation orders farthest-first layer-removal attempts, memory removal and its own
  eviction notifications before returning. Preserve current failing-layer handling; an
  unavailable layer is not promised to lose its record. Admit each publication,
  readmission, write and schedule phase conditionally on generation/closing; callback
  invalidation cannot authorize later obsolete phases. Eviction may load a fresh value.
- No layer IO, callback or wait runs inside admission locks or Shared transactions.
  Closing refuses new work, cancels/joins accepted work and finishes owned callbacks.
  Retention, waiter isolation and finish-all cleanup remain. Key-scoped generations are
  deferred. A versioned Layer protocol or deferred nested invalidation was not chosen.

## Why this shape

Additive awaited release preserves close timing. An owned Resource factory makes
allocation explicit but adds a path; strengthening close is smaller but changes callers.

## Stack

Owner approved the five-entry delivery split and layer rule above on 2026-10-29; release approval remains separate.
- [x] **`codex/0259-flight-release`** — closed admission, cancellation and joined SingleFlight workers ([925](https://github.com/matthewjones372/surefold/pull/925)).
      Done when: startup/cancel races, waiter isolation and reusable cancelAll are proven.
- [x] **`codex/0259-cache-workers`** — privately own background startup, bindings and displaced sleepers ([935](https://github.com/matthewjones372/surefold/pull/935)).
      Done when: accepted work is owned before submission and ordinary close remains interrupt-only.
- [x] **`codex/0259-cache-ordering`** — order conflicting effects and preserve completing callback reentry ([936](https://github.com/matthewjones372/surefold/pull/936)).
      Done when: after-admission invalidation/readmission races, independent-key progress and layer rejection are proven.
- [x] **`codex/0259-cache-release`** — join every cache worker and prevent late publication ([937](https://github.com/matthewjones372/surefold/pull/937)).
      Done when: explicit/ahead refresh, layer/callback cleanup and dependency order are proven.
- [x] **`codex/0259-release-guide`** — resource example and existing-caller compatibility coverage ([938](https://github.com/matthewjones372/surefold/pull/938)).
      Done when: release interruption/self-close decisions and both close paths are documented.

## Acceptance

Tests first: multiple keys/waiters, late registration, blocked and cancellation-resistant
lookup/finally, worker versus cleanup failure reporting, zero-delay refresh, invalidation races,
repeated/concurrent/interrupted/self close and dependency release ordering.
Also prove invalidation after admission, reads during removal, canceled/failed reservation
retirement, independent-key/All boundaries, reentry/invalidation between effect phases,
fresh get from eviction, direct/inherited layer rejection without mutation and global-generation
compatibility. Document foreign-executor limits. Require a concrete per-entry line ledger
before code, near 200 changed non-test lines; further splits need approval, no cap exception.
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
