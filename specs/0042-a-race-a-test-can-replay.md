# 0042 — A race a test can replay

## Problem

A test of concurrent code runs one interleaving, and it's the one the JVM's
scheduler happens to pick on that machine. A race between two forks passes
thousands of times and fails once in CI, and the only reproduction is rerunning
the test and hoping. surefold's own suites work around this with latches and
`Sleeper` (`surefold/src/test/.../Sleeper.kt`) to force an order by hand. A service
testing its own handlers has neither.

Every place a surefold fork waits goes through surefold: `await`, `cancel`, a fork
starting and ending (0037), `send`, `receive` and `select` (0038), and a sleep on
`Clock`. If those are the only places a fork can give way to another, a test
can choose the order — and write it down.

## Not doing

- **No bytecode instrumentation.** JetBrains' Lincheck does model checking by
  rewriting bytecode, aimed at concurrent data structures. This works at surefold's
  own waiting points only, aimed at application code, with nothing rewritten.
- **No determinism outside surefold.** A body blocked in JDBC or `Thread.sleep`
  holds the turn. The run then says it left determinism, and where, rather than
  pretending.
- **No production cost beyond one check** per wait: a thread with no replay
  bound skips the hook.

## Shape

```kotlin
@Test
fun `the cache and the reconciler never both write`() {
    explore(runs = 1_000) {                  // a different seeded order each run
        either<Err, Unit> { flock { async { cache.put(k, v) }; async { reconciler.run(k) } } }
        store.writes(k) shouldBe 1
    }
}
// fails with: order 0x3f2a91c4 broke it on run 212; replay(0x3f2a91c4) { … } runs exactly that order

@Test
fun `the order that broke it`() = replay(seed = 0x3f2a91c4) { … }
```

- A new test-scope module, `surefold-replay`, depending on `surefold` and nothing else.
  It has no JUnit dependency: `explore` throws an `AssertionError` naming the
  seed.
- Under `explore` or `replay`, forks still run on real virtual threads, but only
  one holds the turn. At each waiting point it hands the turn back, and a
  seeded scheduler picks which runnable fork goes next.
- `explore` picks orders by probabilistic concurrency testing (PCT): random
  priorities with a few seeded change points. That finds a bug that needs `d`
  specific orderings with a known lower bound on probability, which uniform
  random choice doesn't give.
- A fork that holds the turn while parked outside surefold for longer than a
  threshold ends the run. The failure names that fork's stack, and says the run
  left determinism there.

## Why this shape

Taking turns at surefold's own waiting points is what makes this affordable. There
are no continuations and no instrumentation, and the order is fully decided by
the seed whenever the code waits only through surefold. The price is honesty about
code that waits elsewhere, which the run reports instead of hiding.

The alternative is to run every fork on one platform thread. That is simpler,
but a body that blocks anywhere deadlocks the test outright. Recommended: real
threads taking turns.

## Stack

- [x] **`spec-0042-seam`** ([#743](https://github.com/matthewjones372/surefold/pull/743)) — in `surefold`, every park and unpark in `Fork`, `Channel` and `Clock`
      goes through one internal `Waiting` function. No change in behaviour.
      Done when: every `surefold` test passes unedited, and `LockSupport` is called
      from one file.
      The seam is `Waiting.kt`: `park`, `unpark` and `sleep`. It also takes `Transact`'s wait for a change and the
      combinators' wait for their forks, which was a `Semaphore` and is now a park the ending forks wake.
      `SystemClock.sleep` parks to its deadline there, so main code has no `Thread.sleep` left. `TestClock` still
      waits on its own lock's conditions: making those waiting points is `spec-0042-channels`.
- [ ] **`spec-0042-replay`** — `surefold-replay`: turns, `explore`, `replay`, PCT, for forks.
      Done when: a planted lost-update race between two `async`s is found within
      1,000 runs, and its seed fails 100 times out of 100 under `replay`.
      The seam is `Turns`, behind the opt-in `@TurnSeam` and bound with `takingTurns` as a `SurefoldLocal`, so a
      fork inherits it as it inherits any binding. A fork is told of before its executor has it (`forking`), so the
      order cannot depend on when a thread starts; an unpark or interrupt marks its thread runnable at once, with
      `LockSupport`'s permit kept in the model. An order is 32 bits, printed as `0x…`. PCT runs with depth 3 over a
      guessed 64 steps. A fork an `await` helps (spec 0037's bounded-pool case) is not modelled: `explore` forks on
      virtual threads, where help does not apply.
- [ ] **`spec-0042-channels`** — channels, `select` and `TestClock` sleeps as waiting points,
      and the report when a run leaves determinism.
      Done when: a producer/consumer ordering bug is found and replayed, and a
      `Thread.sleep` inside a fork fails the run with that fork's stack.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered (maintainer, 2026-10-26: go with the recommendations).

1. **Does it wait for 0037 and 0038?** The seam needs one place to park.
   Recommended: yes. On today's `Fork`, with its latch and lock, the seam would
   be three places, one of them inside `CountDownLatch`.
2. **Where does the seam read the scheduler from?** Recommended: a `ScopedValue`
   bound by `explore`, since 0037 already moves the floor to 25.
3. **Shrinking.** Should a failing order be cut down to the fewest switches
   that still fail? Recommended: later, once people have used replay.
4. **Is a stuck turn a failure or a warning?** Recommended: a failure, because
   a run that isn't deterministic can't promise that its seed replays.
