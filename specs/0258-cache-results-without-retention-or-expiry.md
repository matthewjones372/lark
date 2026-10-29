# 0258 — Cache results without retention or expiry

Approved by the owner2026-10-29: recommendations1–4 and named branch stack agreed; release approval remains separate.

## Problem

A caller needs in-flight sharing without retention, or retention by capacity alone.
`Cache.load` admits zero-TTL entries to `Memory`, where expired entries consume
capacity; infinite TTL can overflow `now + ttl.toJavaDuration()`. Huge TTLs change policy.
Checked v0.16.0 (`6db3daad`) and main `40671b12`: Cache/Memory/Layer sources match.
The Kotlin 2.4.21/JDK 25 expression probe raises ArithmeticException; it is not a Cache test.
Related PRs: [886](https://github.com/matthewjones372/surefold/pull/886), [889](https://github.com/matthewjones372/surefold/pull/889),
[890](https://github.com/matthewjones372/surefold/pull/890), [891](https://github.com/matthewjones372/surefold/pull/891), [902](https://github.com/matthewjones372/surefold/pull/902).

## Not doing

Application policies, another cache/flight registry, new dependencies, a general
retention framework, persistent no-expiry formats, or joined shutdown ([0259](0259-shared-work-that-finishes-before-release.md)).

## Shape

Proposed duration interpretation, subject to the four decisions below:

```kotlin
import io.github.matthewjones372.surefold.cache.Cache
import io.github.matthewjones372.surefold.fail.right
import kotlin.time.Duration

val overlap = Cache<String, Nothing, String>(ttl = { Duration.ZERO }) { it.right() }
val bounded = Cache<String, Nothing, String>(ttl = { Duration.INFINITE }, capacity = 100) { it.right() }
```

- Zero delivers either result to overlapping waiters, but does not admit an entry,
  write a layer, consume retained capacity or start/continue refresh-ahead for it.
  Later calls recheck the layers/source; question 2 settles zero-result layer hits.
  Stopping refresh-ahead never cancels an active lookup needed by waiters; cancelling
  one waiter still leaves the others running.
- Infinite retains an admitted answer without a time deadline until eviction,
  invalidation or owner release; no Instant arithmetic or invented far-future expiry.
- Preserve finite TTL and exact expiry boundaries, result-specific errors, retries,
  bound construction clock, TinyLFU for retained entries and finite stale limits.
- Keep hits/misses/loads/failures/stale accounting; size counts retained entries only.
  No synthetic eviction for an outcome never retained; existing retained-value
  replacement/invalidation callbacks remain once-only under their current rules.
- Preserve invalidation generations: a lookup spanning invalidation may answer its
  waiters but cannot repopulate memory/layers or schedule a refresh for that generation.

## Why this shape

Extending the existing result-specific Duration avoids another policy parameter.
An explicit retention value would separate time from admission more clearly, but
needs an additive API and migration; it remains an option in question 1.

## Stack

Owner agreed these entries and branch names2026-10-29.
- [ ] **`codex/0258-zero-retention`** — zero-result admission, schedules and regression tests.
      Done when: Right/Left overlap shares once; later calls reload without quota/layer writes.
- [ ] **`codex/0258-capacity-only`** — infinite retention and the approved layer policy.
      Done when: clock advance cannot expire it; capacity/invalidation still remove it.
- [ ] **`codex/0258-retention-guide`** — compiled usage and compatibility documentation.
      Done when: zero/stale/callback/layer decisions and finite compatibility are demonstrated.

## Acceptance

Tests first: zero/finite/infinite Right and Left, churn, zero layer hits without readmission,
zero refresh with active waiters, young refresh failure, stale fallback, invalidation races,
once-only callbacks and exact statistics; Promise/Shared/TestClock, no sleep/dependency.
```bash
./gradlew :surefold-cache:test
./gradlew :surefold-app:test --tests '*SpecsSuite'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. Extend zero/infinite Duration or add explicit retention values? Recommend the
   existing signature, documenting zero's observable admission change before adoption.
2. Zero applies only to fresh lookup, or also to layer hits? Recommend deliver a young
   zero-result layer hit without memory/near-layer readmission; leave its shared record
   untouched. Infinite results: reject layers, extend Stored/Redis, or write memory only?
   Recommend memory-only writes: no persistent no-expiry result. Other finite layer hits
   still propagate their original expiry, including when the local policy is infinite.
3. Zero failures with an existing good value: disable stale or preserve current answer selection?
   Recommend preserve stale and young-refresh fallback, without retaining a new zero result;
   remove an expired prior entry after answering, but keep a still-young refresh value.
4. Never-retained values: invoke onEvict or leave their lifetime to their recipient?
   Recommend no callback/no eviction count; existing retained-value removal still calls it.
