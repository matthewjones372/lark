# 0121 — Tests that do not race the runner

## Problem

Since CI moved to GitHub's two-core runners, four tests have each failed once and passed on a re-run. Every such
failure costs a re-run and a comment explaining it, and teaches reviewers to ignore red. Each one has a cause, and
only one of them is in the test alone:

| Test | Line | Why it fails on a slow runner |
|---|---|---|
| `ParTest` › when two branches fail, the one started first surfaces | `lark` | **A library race.** If the second branch's raise is seen first, `Flight.settle` cancels the first before it has recorded its own raise. `Fork.cancel` then marks it `cutShort`, and `ownFailure` drops its genuine raise, so "second" surfaces. 2 failures in 28 local runs. |
| `TopicTest` › a stalled subscriber stops nothing… | `lark-actor`:123 | `KEEP_AT_MOST + 2_001` publishes go out at once. The healthy subscriber `"a"` has to drain faster than the topic publishes, or its own bounded mailbox fills and it loses messages too. |
| `MeasuredTest` › stages upstream of the slow one wait… | `lark-stream-parity`:136 | The "slow" stage sleeps 2 ms. Scheduling noise on a loaded runner is the same size, so neither the `0.9 × SLOW` bound nor the 10× ratio is reliable. |
| `LeaveOnReleaseTest` › a cluster … leaves it as the application is released | `lark-app-actor`:68 | It asserts the release took under 10 s of wall-clock time, to tell "removed by the other node" apart from "downed itself after 20 s". With `ackWithin = 60 ms`, a loaded runner suspects nodes and slows the leave. |

## Not doing

- **Retries, `@RepeatedTest` filters or quarantine.** A test that is red one time in twenty stays a failing test.
- **Raising timeouts across the board.** Each change addresses the one bound that is wrong.
- **A slower CI runner profile** or a `@Tag("slow")` split.

## Shape

1. **`Flock`: a raise counts even if the fork was cut short.** An interrupt reaches a fork as a *throw*
   (`InterruptedException`, or whatever a pool wraps it in), never as a `raise`. So `ownFailure` drops only a
   cut-short **throw**: `takeUnless { cutShort && it is Thrown }`. A fork that raised, before or after the cancel,
   raised of its own accord, and start order then holds as `ParTest` claims.
2. **`TopicTest`: pace the publisher, not the stalled subscriber.** Publish in chunks of `KEEP_AT_MOST / 10`, and
   wait until `"a"` has heard each chunk before sending the next. `"slow"` stays stalled on its first message, so it
   still fills and overflows, and the test still asserts exactly that. `"a"` never holds more than a chunk.
3. **`MeasuredTest`: a slow stage that is slow.** Raise `SLOW_MILLIS` from 2 to 25. That is 0.5 s over 20 elements,
   an order of magnitude above scheduler noise. The `0.9 ×` and `10 ×` claims stay as they are.
4. **`LeaveOnReleaseTest`: assert how the node left, not how long it took.** The test already asserts that the
   `"app"` member's last event is `Removed` with status `Leaving`. A node that downed itself would be removed as
   `Down`, so that assertion already tells the two cases apart. The wall-clock bound becomes
   `released.elapsedNow() shouldBeLessThan 20.seconds`: the self-down path's own `stableAfter`, the one figure that
   actually separates them.

## Why this shape

Three of the four are tests asserting more than their claim needs. Each change keeps the claim and removes the
dependence on the runner's speed. The fourth is a real ordering bug that the test was right to catch. Fixing
`ownFailure` is a one-line change, and the reason fits the existing comment beside it: an interrupt arrives as a
throw. The alternative, making `ParTest`'s second branch wait longer, would hide a bug that users hit too: two
branches that both raise at nearly the same moment surface the wrong error.

## Depends on

Nothing.

## Stack

- [ ] **`spec-0121-flock-start-order`** — `Fork.ownFailure` keeps a cut-short fork's raise, in `lark`.
      Done when: a new test, in which the first branch's raise is deliberately delayed until after the cancel,
      surfaces "first"; `ParTest` passes 200 runs out of 200 under `stress` (below); every other `lark` test still
      passes.
- [ ] **`spec-0121-timing-tests`** — the `TopicTest`, `MeasuredTest` and `LeaveOnReleaseTest` changes above, tests
      only.
      Done when: each passes 50 runs out of 50 under `stress`.

## Acceptance

```bash
# stress: two busy cores beside the test JVM, as on a GitHub runner under load
( for i in 1 2; do timeout 900 sh -c 'while :; do :; done' & done )
for n in $(seq 1 50); do ./gradlew :lark-actor:test --tests '*TopicTest*' --rerun -q || exit 1; done
./gradlew spotlessApply && ./gradlew build
```

## Settled

1. **Does the `Flock` fix belong in this spec?** Yes. Its only effect is that the documented start-order behaviour
   now holds.
2. **Does a fork that catches `InterruptedException` and raises in response count as raising of its own accord?**
   Yes. A raise is always a deliberate value, and AGENTS.md already says to catch the exception you expect by name.
3. **For `LeaveOnReleaseTest`, a 20 s bound or none?** A 20 s bound. The status assertion covers how the node left,
   and the bound still catches a leave that hangs.
