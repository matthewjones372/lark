# 0143 — A runner that waits for the next message

## Problem

A runner that finds the queue empty spins for 10 µs, then removes itself from `started`, decrements `active` and
ends its virtual thread. The next `submit` sees `active` below `parallelism` and starts a new one, even while the
old runner is still spinning, because a spinning runner counts as running but nothing tells `submit` it is free.
One busy actor whose mailbox empties between messages therefore costs a thread start and a retirement per message.

Petshop hit this at 50,000 req/s, every request an ask to one actor: JFR put 17.5% of the shop's CPU in
`ConcurrentHashMap$KeySetView.remove` under `Runners.run`, 13% of its allocation in virtual thread stack chunks,
and about 2% each in `ForkJoinPool.signalWork` and `workArrives`.

Measured here with a new `AskBenchmark` (64 virtual threads asking one actor, 1,000 asks each), on main:

| | runner starts / activations | samples in start or retire | ns per ask (JMH, 1 fork) |
|---|---|---|---|
| main | 498,967 / 502,538 (0.99 each) | 525 of 941 (`Object.hashCode` for `started`: 150) | 854 ± 293 |
| prototype below | 13 / 3,104,039 | none (no `hashCode`, no `KeySetView`) | 584 ± 204 |

Pekko's row on the same run is 541 ± 247 ns. Process CPU per ask stayed near 3 µs on both: the work saved is now
spent spinning. Wall time was taken on a busy machine (load 30 to 80 on 8 cores) and two forks there disagreed
(1,063 ± 148 against 1,114 ± 500), so the start count is the claim; the timing is a hint.

## Not doing

- **Changing the spin.** `SPIN_NANOS` stays 10 µs. Where the CPU goes after this is a separate question.
- **The watcher.** Its doubling under blocked runners is unchanged.
- **The ask path.** `LINGER_NANOS` and `Answer` are untouched.

## Shape

Nothing public changes. Inside `Runners`:

- `submit` offers, then signals only when no runner is spinning. A spinner's last look at the queue comes after it
  stops counting itself, so an offer it missed is seen by the submit, or by the spinner.
- `signal` wakes a parked runner, else grows to `parallelism`, else starts the watcher, as `submit` does now.
- A runner whose spin finds nothing parks on a stack for up to `KEEP_NANOS` (1 s) instead of leaving. After
  pushing itself it looks at the queue once more and claims itself if work is there.
- A waker claims a parked runner with a CAS on its state (`RUNNING`, `PARKED`, `RETIRED`) and unparks it. A runner
  whose park times out CASes itself to `RETIRED` and leaves as today, `rejoin` included.
- A runner that takes an activation and still sees work queued, with nobody spinning, signals once more, so a
  burst that found one spinner still spreads to `parallelism` runners.
- Each runner is on the stack at most once (a `listed` flag); stale entries are skipped by the waker.

`awaitIdle`, the urgent queue (0104) and the after-the-last-poll `rejoin` keep their current code.

## Why this shape

Parking keeps a runner past a short gap at the cost of one unpark, where today the gap costs a thread start, two
set operations and an identity hash. The alternative is a longer spin with no park: it removes the churn too but
burns a carrier for the whole gap, which is the CPU this spec is trying to give back. Recommended: park.

A first prototype used `ConcurrentLinkedDeque` with `removeFirstOccurrence` on self-claim; its dead nodes made
`linkFirst` the top frame. A Treiber stack with a fresh node per push, and no removal, does not show up.

## Stack

- [x] **`spec-0143-ask-benchmark`** ([#420](https://github.com/matthewjones372/lark/pull/420)) — `AskBenchmark` in `surefold-actor-benchmarks`, surefold against Pekko.
      Done when: `./gradlew :surefold-actor-benchmarks:jmh -PbenchmarkArgs="AskBenchmark"` prints both rows.
- [x] **`spec-0143-parked-runners`** ([#421](https://github.com/matthewjones372/lark/pull/421)) — runners park and are woken; a `RunnersTest` that fails on main.
      Done when: 10,000 activations submitted one after another start at most `parallelism` runners, and
      `./gradlew build` is green.

## Acceptance

```bash
./gradlew :surefold-actor:test --tests '*RunnersTest*'
./gradlew :surefold-actor-benchmarks:jmh -PbenchmarkArgs="AskBenchmark"
./gradlew build
```

## Open questions

1. **How long does a parked runner wait?** 1 s keeps it across any gap a busy actor has; up to `cap` (2,048)
   parked threads after a blocking burst is a few hundred KB. Recommended: 1 s.
2. **How does the test count starts?** An `internal` counter on `Runners`, read by `RunnersTest`, or a thread
   factory passed in. Recommended: the counter; nothing else needs a factory.
3. **Should the timed park become untimed** for the first `parallelism` parked runners, so none registers a timer?
   Parks are 1.5% of activations in the benchmark. Recommended: no, until a profile says otherwise.
4. **Is CPU per ask the next target?** It did not move, because the spin now absorbs the gap. Recommended: a
   follow-up spec once this lands, measured on a quiet machine.
