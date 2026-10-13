# 0130 — A transactional run that restarts cleanly

## Problem

`TransactTest` has failed on `main` twice (runs 37037444778 and 38458164384; the second passed on a re-run). 94 local
runs under CPU load failed 3 times, all in `a restart after an aborted transaction writes each record exactly once` on
Pekko. Taking the test's timing out exposed a deterministic failure on Forks.

1. **The test assumes one batch.** Three records, `within = 200.milliseconds`, one transaction expected. Pekko's
   `groupedWithin` ticks from when the stage starts, so a tick can land between records. One run committed `1, 2`,
   failed on `3` alone, restarted at offset 2, and the restarted `take(3)` waited for ever. The abort case leans on the
   same thing with 5 seconds.
2. **Forks closes a given-up attempt's source while it is still reading.** `restartOnDefect` calls `givenUp`, which
   closes the attempt's `Opened` resources without waking them, from the restart's thread. A `groupedWithin` feed is
   inside `poll` on its own fork, so `KafkaConsumer.close` throws `ConcurrentModificationException`. The exception is
   kept as a suppressed cause, the old consumer keeps its partition, and the new attempt never sees a record.
   `consume().transacted().restartOnDefect()`, the pattern `transacted`'s KDoc names, hangs on every restart. The test
   hides it: its `take(3)` ends the first attempt's source before the defect.
3. **A hang says nothing.** A timed-out test reports only the test thread. JUnit's own dump on timeout
   (`junit.jupiter.execution.timeout.threaddump.enabled`) uses `Thread.getAllStackTraces()`, which leaves out virtual
   threads, and every Forks body and feed is one. The abort-case hang in run 38458164384 is still unexplained for this
   reason.

The other two local failures were a stop racing its last transaction's group metadata; spec 0131 takes that.

## Not doing

- **Explaining the abort-case hang here.** Not reproduced in 94 runs; 3 is what makes the next one explain itself.
- **Longer timeouts.** Nothing waits longer than it does today.
- **A dump on timeout outside `lark-kafka`.** See Open questions.

## Shape

- **Close after the last read.** `Opened.close()` wakes the resource and waits for a `next` in flight to return before
  `close`, which is what `Stream.blocking` already promises. Both backends close through `Opened`, so both get it.
- **Batches by count in the tests.** `transacted(orders, batch = 3, within = GENEROUS_SECONDS.seconds)` for each batch a
  case depends on. The restart case has no `take` upstream, so it is the reproducer for 2, and ends with `.take(1)`
  after its commit.
- **Every thread on timeout.** A `PreInterruptCallback` in `lark-kafka`'s tests writes
  `HotSpotDiagnosticMXBean.dumpThreads(path, TEXT)`, virtual threads included, to stderr before JUnit interrupts the
  test. Stderr is in the XML report CI already uploads.

## Why this shape

For 2, the fix could sit in `Resources.givenUp` (wake, then close) instead, but that still closes on the restart's
thread while the feed may be mid-`poll`; waiting in `Opened` keeps the contract in the one place both backends share.
For 3, JUnit's own dump is one property and no code, but it misses exactly the threads a Forks hang is on.

## Stack

- [ ] **`spec-0130-threads-on-timeout`**: the callback, registered for `lark-kafka`'s tests by extension autodetection.
      Done when: a test that parks a virtual thread and times out prints that thread's stack in its report.
- [ ] **`spec-0130-close-after-read`**: `Opened.close()` wakes and waits for a read in flight.
      Done when: a Forks test of `restartOnDefect` over a `Stream.blocking` whose `next` blocks until woken never sees
      `close` overlap `next`, and the next attempt opens only after the last one closed.
- [ ] **`spec-0130-one-batch`**: `TransactTest` batches by count, and the restart case drops its upstream `take`.
      Done when: `./gradlew :lark-kafka:test` passes 100 runs in a row under load, on both backends.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
for i in $(seq 100); do ./gradlew :lark-kafka:test --rerun -q || break; done
```

## Open questions

1. **The dump for every module?** Every Forks test runs on virtual threads. Recommend `lark-kafka` first, and moving
   the callback to a shared test module once it has caught something.
2. **A dump file or stderr?** Recommend stderr: the report CI uploads holds it, and a file would need its own upload.
