# 0144 — A runner spins while spinning pays

## Problem

Since 0143 a runner that runs dry spins for 10 µs, then parks on a stack until a submit wakes it. An actor asked
at a steady pace whose gaps are longer than the spin pays for the whole spin and the park on every message. Petshop
at 50,000 asks a second to one actor (gaps near 20 µs) re-measured after 0143 with surefold still at about 15% of its
CPU: JFR put 10.7% in `Runners.push`, 4.2% in `ConcurrentLinkedQueue.first` under `nothingQueued()`, and 13% of
allocation in virtual thread stack chunks.

`AskBenchmark` does not show it: 64 askers in a closed loop leave gaps shorter than the spin, so only 2.5% of
activations park. A probe that paces the same 64 askers to 50,000 asks a second does (process CPU per ask, median
of 8 rounds of 2 s, 8 cores, interleaved runs, load 6 to 15):

| | CPU per ask, paced 50k/s | CPU per ask, closed loop | parks per activation, paced |
|---|---|---|---|
| main | 29.3 µs | 3.1 µs | 0.96 |
| spin 0 | 17.2 µs | 2.5 µs | 1.00 |
| prototype below | 17.5 µs | 2.9 µs | 0.99 |

The spin is the cost: 12 µs a message. A push and a pop still happen on every activation in all three rows, and
removing the spin alone takes the CPU down by 40%, so the stack's CAS is not where the CPU goes. JFR's execution
samples land on the lines beside the park (in the probe, line 196's queue check and `parkNanos` drew exactly 199
samples each), which is how petshop's profile came to name `push`.

## Not doing

- **The parked stack.** Striped or per-carrier stacks fix contention this benchmark does not show.
- **The park itself.** Its stack chunks are the JDK's price for a virtual thread that waits; the watcher and
  `KEEP_NANOS` stay.
- **The ask path.** `LINGER_NANOS` and `Answer` are untouched.

## Shape

Nothing public changes. Inside `Runners`:

- Each runner keeps its own spin, starting at `SPIN_NANOS` (10 µs). Only the runner reads or writes it.
- A runner that finds work during its spin keeps its spin.
- A runner woken from the park within `SPIN_NANOS` of running dry resets its spin to `SPIN_NANOS`: a full spin
  would have caught that work. Woken later, it halves its spin, so after about a dozen wide gaps it parks at once.
- `submit`, `signal`, `parks`, `awaitIdle`, the urgent queue (0104) and the after-the-last-poll `rejoin` keep their
  current code. A runner that spins less is counted in `spinning` for less time; nothing relies on how long.

## Why this shape

A spin pays when the next message comes before the spin ends, and a hand-off chain (an ask through a shard region,
a ping-pong) depends on it. With the spin at 0, `ShardedAskBenchmark` local went from 3.9 to 14.2 µs (± 14, one fork). The paced
actor wants no spin at all. A fixed shorter spin splits the difference and suits neither. Halving on a wasted spin
and resetting on a near miss follows each runner's recent gaps, at the cost of a `nanoTime` per park. Recommended:
adapt per runner.

JMH on the prototype, 1 fork each, two runs per side, load up to 20: Ask 508 / 507 against 519 / 458 ns, ShardedAsk
local 4.2 / 4.1 against 4.6 / 3.7 µs, Tell one-to-one 64 / 72 against 61 / 63 ns, PingPong 35.5 / 29.9 against
32.3 / 29.8 µs, FanOut 1,824 / 1,852 against 1,496 / 1,652 µs. All within error.

## Stack

- [x] **`spec-0144-paced-ask`** ([#716](https://github.com/matthewjones372/surefold/pull/716)) — an `askCpu` task in `surefold-actor-benchmarks` that prints process CPU per ask,
      closed loop and paced to 50,000 a second, for surefold and Pekko.
      Done when: `./gradlew :surefold-actor-benchmarks:askCpu` prints four rows.
- [x] **`spec-0144-adaptive-spin`** ([#719](https://github.com/matthewjones372/surefold/pull/719)) — each runner halves its spin after a wasted one; a `RunnersTest` that fails
      on main.
      Done when: activations submitted 50 µs apart spin for less than a quarter of 10 µs each, and
      `./gradlew build` is green.

## Acceptance

```bash
./gradlew :surefold-actor:test --tests '*RunnersTest*'
./gradlew :surefold-actor-benchmarks:askCpu
./gradlew :surefold-actor-benchmarks:jmh -PbenchmarkArgs=".*\.(Ask|ShardedAsk|Tell|PingPong|FanOut)Benchmark\.surefold"
./gradlew build
```

## Open questions

1. **Adapt, or drop the spin?** Dropping it is one line and wins the paced case, but costs a hand-off chain about
   10 µs a hop. Decided: adapt.
2. **Per runner, or one spin shared by the flock?** Shared learns faster but is a field every runner writes.
   Decided: per runner.
3. **How does the test see the spin?** An `internal` total of nanoseconds spun, read by `RunnersTest`, as `starts`
   is. Decided: that counter.
4. **Is the paced probe a JMH benchmark?** JMH times a paced run at the pace, so it says nothing; CPU is what moves.
   Decided: a plain `main` task beside `footprint`.
