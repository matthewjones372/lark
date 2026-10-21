# 0165 — a consumer closes without waiting on the broker's held fetch

## Problem

Spec 0154 left one cost, its open question 1: about 500 ms of every 50k-record run is `KafkaConsumer.close()`,
the Java client's own loop included. `close()` has two phases. The first sends the group leave and waits for it:
a few milliseconds. The second sends a fetch-session close, which queues behind the fetch the broker holds for
`fetch.max.wait.ms` (500 ms by default), and waits up to the rest of the close timeout (30 s by default) for it.

Since 0154's async commit, a run that ends early hits it too: a `take` or a stop interrupts the read-ahead
poll with its fetch already sent, where a `commitSync` used to absorb the interrupt. `larkMapPar` handles 5k
records in 135 ms and then closes for 500 ms (136 → 640 ms).

Measured (`closeProbe`, embedded broker, five runs each, after an interrupted read-ahead poll):

| close | took | group after | committed |
|---|---|---|---|
| `close()` | ~495 ms | Empty | all |
| `close(5 × last commit round trip, ≥ 20 ms)` | 28–74 ms | Empty | all |

## Not doing

- Committing less often. Measured: async commits before each poll cost polling nothing (49 ms against 53 ms
  without); the ~22 ms left is the final `commitSync` waiting for those in flight, which an interval keeps.
- A `closeTimeout` parameter. Nobody would set it; the budget below is derived, not chosen.
- Pekko's connector, `Kafka.subscribe`: it closes its own consumer.

## Shape

No API change. `Commits.close()` commits as now, timing the round trip, then
`consumer.close(CloseOptions.timeout(max(5 × roundTrip, 10 ms)))`:

- the commit sent nothing → the round trip of `consumer.committed(assignment)`, one request to the coordinator;
- the commit threw → `close()` as today;
- no group → nothing to leave, so the floor alone.

## Why this shape

The leave is one request to the coordinator the commit just reached, so the commit's round trip bounds it;
five times over leaves room for a slow moment. A fixed short timeout would lose the leave on a slow network,
and the group would wait out `session.timeout.ms` (45 s by default) before rebalancing. Offsets are committed
before the close either way, so the worst case is that wait, never a lost commit.

## Stack

- [ ] **`spec-0155-close`**: the budget above. Done when: a consumer whose broker holds a fetch for 3 s
      closes in under 1 s, leaves its group empty, and has committed what it handled.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-kafka-benchmarks:jmh -PbenchmarkArgs="-wi 10 -i 10 -r 1 -w 1 -t 1 -f 5 .*ComparisonBenchmarks.*"
```

## Open questions

1. **Five times, and what floor?** Five times the round trip bounds the leave; the floor only matters where the
   round trip is tiny, as on an embedded broker. A 50 ms floor cost `larkMapPar` 50 ms a run where the round trip
   was 5 ms. **Answered:** five times, 10 ms floor.
2. **Should the Java rows in the benchmark close the same way?** They show what a hand-written loop pays.
   Recommended: no, and the README says how lark's close differs. **Answered:** no.
