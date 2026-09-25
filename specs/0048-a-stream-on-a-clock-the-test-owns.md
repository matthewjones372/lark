# 0048 — A stream on a clock the test owns

## Problem

A pipeline with time in it cannot be tested quickly. `tick`, `groupedWithin`
and `restartOnDefect` wait on real time through Pekko's scheduler. So a test of
the petshop relay either sleeps or shrinks every duration until the test
measures the machine's speed rather than the pipeline. lark already has
`TestClock` for `Schedule`, but a Pekko stage never asks it the time.

Once 0046 lands, a backend is a value. A backend that runs the tree on the
calling thread, on lark's `Clock`, gives a test full control of time without
touching the pipeline.

## Not doing

- **No concurrency.** `mapPar` and `flatMapMerge` run one element at a time in
  input order. That is the right answer for a timing test and the wrong one for
  a race, which is 0042's job.
- **No speed claims.** This backend is for tests, not production.
- **No `Native` nodes.** A Pekko `Source` has its own clock. `start` refuses the
  run, naming the node.

## Shape

In a new module `lark-stream-test`, depending on `lark-stream` and nothing else:

```kotlin
val clock = TestClock()
val running = relay.start(TestStreams(clock))

clock.adjust(1.seconds)                     // one tick is due, and runs to completion
running.emitted() shouldBe listOf(batch1)
clock.adjust(30.seconds)
running.exit shouldBe Exit.Done(…)          // already complete, nothing to wait on
```

- `TestStreams(clock)` runs every stage on the calling thread. Each `adjust`
  drains all work that becomes due at or before the new instant, in time order,
  before it returns.
- `tick`, `groupedWithin`, `restartOnDefect`'s schedule and 0045's backoff read
  only the backend's clock.
- `emitted()` returns what the sink has received so far, for a sink that
  collects.

## Why this shape

The test owns time because the pipeline never names a clock. Only the backend
does. The alternative is a `TestKit`-style Pekko scheduler, which is Pekko-only
and still runs on real threads. Recommended against.

## Stack

- [ ] **`spec-0048-backend`**: `lark-stream-test`, `TestStreams`, and every operator
      without time on the calling thread.
      Done when: the 0046 parity suite passes on it for every operator it supports.
- [ ] **`spec-0048-time`**: `tick`, `groupedWithin` and restart on the test clock.
      Done when: a test of an hour-long `tick` finishes in under a second, and asserts
      exactly 60 elements after `adjust(1.hours)` at one per minute.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `adjust` block until work is drained, or does it return a handle?**
   Recommended: it blocks. A test reads top to bottom.
2. **How does the backend learn that the clock moved?** `TestClock.adjust`
   notifies a listener, or the test calls `running.adjust(by)` instead.
   Recommended: the listener, so a `Schedule` test and a stream test move time
   through the same call.
3. **Should the petshop relay test move over as the first consumer?**
   Recommended: yes, in the petshop repository, after `spec-0048-time`.

Decided while building `spec-0048-backend` (2026-09-25), for editing:
`TestStreams` is the Forks pull loop run on the calling thread under its own
name, not a second runner, so it runs and refuses exactly the operators Forks
does. `lark-stream-test` therefore depends on `lark-stream-forks`, which brings
nothing but `lark-stream`. It takes its `TestClock` now, and `spec-0048-time`
is what reads it. Until then, a stream that never ends and never meets a `take`
blocks the test that runs it.
