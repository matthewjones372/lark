# 0128 — A test runtime that can be unkind

## Problem

`testActors` (lark-actor) steps actors on the test's thread with an in-memory journal, timers in test time and a
`TestClock`, so a service's real behaviours run in memory, deterministically. But it is always kind: every message
arrives, once, in the order it was told, and an actor only stops when it fails. The bugs that matter in a
distributed system are about the unkind cases: an answer lost and the request retried, a retry's twin arriving
second, a node dying between two steps and its entity recovering from the journal elsewhere. Today the only way to
reach those is a real cluster, which is slow (lark-bank: five minutes a check, 23 of 26 app specs) and can't replay a
failing ordering.

lark-bank needs this for its transfer saga (bank spec 0028), and any Lark service with sagas or retries does too.

## Not doing

- **Simulating the network between nodes, sharding or the receptionist across nodes.** Faults apply to messages
  between test actors in one runtime.
- **Changing `testActors`' default.** Without an order or faults it behaves exactly as today.
- **Property-testing helpers.** Seeds and faults are inputs; which properties to check is the service's.

## Shape

```kotlin
val actors = testActors(
    order = Ordering.seeded(42),               // which queued message runs next; default: first in, first out
    faults = Faults(drop = 0.1, duplicate = 0.1, delay = 0.1, maxDelay = 2.seconds),
)
val account = actors.spawn("acc-1", account(...))
actors.crash(account)                          // stops it now; its next message recovers it from the journal
actors.settle()                                // runs messages and timers to idle, advancing test time as needed
actors.trace                                   // every delivery, drop, duplicate and crash, in order, for a failure
```

- **Seeded order.** Picks, with a seeded random source, which of the queued messages runs next, keeping each
  sender's messages to one receiver in order (as remoting does), so interleavings vary but stay legal.
- **Faults.** Applied to tells between actors, and to replies, by probability from the same seed. A dropped or
  delayed message is recorded in the trace; a delay is in test time, falling due on `advance`/`settle`.
- **Crash.** Stops an actor as a node's death would: its mailbox and timers are lost, and the next message to it
  starts a new incarnation that recovers from the journal and snapshots. Faults can also crash actors by probability.
- **Replay.** The same seed, behaviours and test steps give the same trace, so a failing seed reproduces exactly.

## Why this shape

Faults belong in the runtime because only it sees every tell, reply and timer; a service wrapping its own refs
(lark-bank's workaround) misses replies and timers. One seed for the order and the faults makes a failure one number to
replay. FoundationDB's and TigerBeetle's deterministic simulation are the model, scaled down to one process's actors.

## Stack

- [x] **`seeded-order`** ([#358](https://github.com/matthewjones372/lark/pull/358)) — `Ordering.seeded`, per-sender order kept, `trace`. Done when: a test shows two seeds
      give different legal interleavings and one seed always the same.
- [x] **`message-faults`** ([#358](https://github.com/matthewjones372/lark/pull/358)) — drop, duplicate, delay for tells and replies. Done when: a retrying requester under
      `drop = 0.5` still completes, and the trace shows each fault.
- [x] **`crash-recover`** ([#358](https://github.com/matthewjones372/lark/pull/358)) — `crash(actor)` and crash by probability, recovery from journal and snapshots. Done when:
      a persistent actor crashed between two persists recovers the first event's state and nothing more.

## Acceptance

```bash
./gradlew :lark-actor:test
```

## Open questions

- Should faults apply to timers too (a timer lost with a crash is already covered)? Recommended: no.
- `settle()` bound: stop after N steps and fail, so a livelock shows as a failure? Recommended: yes, default 100,000.
