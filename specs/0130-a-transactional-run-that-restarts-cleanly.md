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
4. **A stop on Forks loses the consumer's last commit.** Found by the 100-run check: `ConsumeTest`'s `mapParRecord` case
   committed 2 or 1 of 3 offsets in 3 of 100 runs, and in 1 of 36 runs before 2's fix. A `take` that ends the run
   interrupts `mapPar`'s feed while it is in `poll`, and the consumer is then closed on that interrupted thread, so
   `Commits.close()`'s `commitSync` throws `InterruptException` on every Forks stop. What it held back is committed only
   if the loop's commit before its next 100ms poll came first; otherwise a new consumer reads it again.
5. **`readAll` can read before a transaction has ended.** Also found by the check. `commitTransaction` returns once the
   coordinator has prepared the commit; the marker that ends the transaction is written after, and until it lands a
   `read_committed` reader stops short of it. The restart case read nothing once in 13 runs.

The other two local failures were a stop racing its last transaction's group metadata; spec 0131 takes that.

## Not doing

- **Explaining the abort-case hang here.** Not reproduced in 94 runs; 3 is what makes the next one explain itself.
- **Longer timeouts.** Nothing waits longer than it does today.
- **A dump on timeout outside `surefold-kafka`.** See Open questions.

## Shape

- **Close after the last read.** `Opened.close()` wakes the resource and waits for a `next` in flight to return before
  `close`, which is what `Stream.blocking` already promises. Both backends close through `Opened`, so both get it.
- **Batches by count in the tests.** `transacted(orders, batch = 3, within = GENEROUS_SECONDS.seconds)` for each batch a
  case depends on. The restart case has no `take` upstream, so it is the reproducer for 2, and ends with `.take(1)`
  after its commit.
- **A close that commits whatever thread it runs on.** `Commits.close()` clears the thread's interrupt around its commit
  and `consumer.close()`, and sets it again after, so an interrupt that stopped the read does not also stop the commit.
- **`readAll` after the transaction ends.** The test broker's `readAll` waits until no transaction is open on the
  partition, within its 30-second deadline, before it reads.
- **Every thread on timeout.** A `PreInterruptCallback` in `surefold-kafka`'s tests writes
  `HotSpotDiagnosticMXBean.dumpThreads(path, TEXT)`, virtual threads included, to stderr before JUnit interrupts the
  test. Stderr is in the XML report CI already uploads.

## Why this shape

For 2, the fix could sit in `Resources.givenUp` (wake, then close) instead, but that still closes on the restart's
thread while the feed may be mid-`poll`; waiting in `Opened` keeps the contract in the one place both backends share.
For 3, JUnit's own dump is one property and no code, but it misses exactly the threads a Forks hang is on.

## Stack

- [x] **`spec-0130-threads-on-timeout`** ([#368](https://github.com/matthewjones372/lark/pull/368)):
      the callback, registered for `surefold-kafka`'s tests by extension autodetection.
      Done when: a test that parks a virtual thread and times out prints that thread's stack in its report.
- [x] **`spec-0130-close-after-read`** ([#370](https://github.com/matthewjones372/lark/pull/370)):
      `Opened.close()` wakes and waits for a read in flight.
      Done when: a Forks test of `restartOnDefect` over a `Stream.blocking` whose `next` blocks until woken never sees
      `close` overlap `next`, and the next attempt opens only after the last one closed.
- [x] **`spec-0130-commit-when-interrupted`** ([#373](https://github.com/matthewjones372/lark/pull/373)):
      `Commits.close()` commits and closes with the interrupt held aside.
      Done when: a Forks `runCommitting` run that a `take` ends commits every record it handled, with the closing
      thread interrupted, and the thread is still interrupted afterwards.
- [x] **`spec-0130-one-batch`** ([#384](https://github.com/matthewjones372/lark/pull/384)):
      `TransactTest` batches by count, the restart case drops its upstream `take`, and
      `readAll` waits for the partition's transactions to end.
      Done when: `./gradlew :surefold-kafka:test` passes 100 runs in a row under load, on both backends.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
for i in $(seq 100); do ./gradlew :surefold-kafka:test --rerun -q || break; done
```

## Open questions

1. **The dump for every module?** Every Forks test runs on virtual threads. Recommend `surefold-kafka` first, and moving
   the callback to a shared test module once it has caught something.
2. **A dump file or stderr?** Recommend stderr: the report CI uploads holds it, and a file would need its own upload.
