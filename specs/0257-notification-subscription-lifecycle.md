# 0257 - Observe a notification subscription's lifetime

**Approved by the owner on 2026-10-29; implementation branch and recommendations 1-3 accepted.**

## Problem

A service adopting `PostgresNotifications.listen` cannot know when its first subscription has succeeded or
when that subscription is lost. `Received` may arrive much later and initial `Gap` would falsely report missed
notifications. Consumers currently need a JDBC wrapper or a readiness query to preserve honest readiness.

## Not doing

No new module, dependency, notice, setting, supervisor, delivery guarantee or reconnect-policy change.
No consumer-specific health policy, JDBC cancellation adapter or publication from a developer machine.

## Shape

Keep the existing `listen(source, channels, reconnect = RECONNECT)` overload and its JVM/default signatures.
Add an overload with required lifecycle callbacks and an optional reconnect schedule:

```kotlin
PostgresNotifications.listen(
    source,
    channels = setOf("bookings_live"),
    onListening = { readiness.update { true } },
    onDisconnected = { readiness.update { false } },
)
```

The new signature is `listen(source: DataSource, channels: Set<String>,
reconnect: Schedule<Throwable, Any?> = RECONNECT, onListening: () -> Unit,
onDisconnected: () -> Unit): Stream<Nothing, Notice>`. The old overload delegates with no-op callbacks.

- `onListening` runs after every channel's actual `LISTEN` succeeds, before `Gap` or notifications.
- `onDisconnected` runs once for each established subscription, after its connection releases on loss, normal
  completion, stop or failure. An initial connection or `LISTEN` failure produces neither lifecycle callback.
- Callbacks run synchronously on the owned pull run. `Running.close` joins them; none continues after release.
- Callback defects, including a callback's `SQLException`, end the run as `Exit.Died`; they are outside the
  database retry catch. Cancellation is never retried. A failed `onListening` still releases its established
  connection and invokes `onDisconnected` once. Clearing ownership before release prevents duplicate callbacks.
- Keep exactly the existing notices: no initial gap, one gap after a lost subscription returns, then later
  notifications in order. Existing calls and compiled binaries keep their default reconnect behaviour.

## Why this shape

Non-emitting callbacks let the consumer own its readiness policy while the stream retains connection lifetime,
ordering and release. A new `Ready` notice changes an exhaustive sealed API and data processing; a Boolean or
new state enum adds less explicit meaning or another type. Recommend two named callbacks in an additive overload.

## Stack

- [x] **`codex/0257-notification-lifecycle`** ([#921](https://github.com/matthewjones372/surefold/pull/921)) -
      overload, connection-lifecycle calls, regressions and guide.
      Done when: real Postgres proves readiness/disconnect ordering and joined release, old calls still compile,
      callback defects do not reconnect, and notices retain the existing initial/reconnect semantics.

This is one implementation PR, expected below 200 changed main/build/docs lines; tests are excluded from that cap.
The approved spec lands on `main` alone before this branch is implemented. Release uses the existing CI workflow
and staged Central Portal review after tests and review; no secrets or local publishing are part of this slice.

## Acceptance

Failing tests first: multi-channel subscription success; initial connection and statement failure; disconnect
then reconnect and one gap; normal completion/close; callback failure and `SQLException`; release while a callback
is in flight. Use resources, `Shared`, `Promise`, public scoped forks, test clocks and real Postgres for SQL claims.
Retain existing notification/reconnect/jobs tests, dependency guards and compiled guide examples.

```bash
./gradlew :surefold-postgres-notify:build :surefold-jobs:build
./gradlew spotlessApply
./gradlew build
```

## Open questions

1. Callback pair versus one state callback? **Accepted:** the pair shown above, with no new public type.
2. Disconnect on downstream completion as well as loss? **Accepted:** yes, once per established subscription.
3. Callback `SQLException` is a defect rather than a reconnect? **Accepted:** yes; caller effects are not JDBC retry.
4. Release version? **Accepted:** `0.16.0`, published on Maven Central on 2026-10-29.
   Tag `v0.16.0` names merge `6db3daad0fcdb739a9000a3357a90242033ab7f7`.
