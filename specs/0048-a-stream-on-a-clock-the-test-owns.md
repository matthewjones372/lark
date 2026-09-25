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

Decided while building `spec-0048-time` (2026-09-25), for editing: the run is
no longer on the calling thread. Waiting on a time needs a stack to wait on, and
the calling thread's is the test's. So a run is workers on virtual threads that
take turns: one runs at a time, and the others are parked on an instant or on
something another worker hands them. `start` returns once no worker can go on,
which for a run with no time in it is when it is over, as before. Question 2
went as recommended: `TestClock` takes a `Waiter`, and `adjust` stops at each
instant a waiter asks for on its way and lets it settle there, so a `Schedule`
test and a stream test move time through the same call. `groupedWithin` pulls
upstream on a worker of its own, so a window can close while an element is
still to come. When an element and a window's close fall due at the same
instant, the element arrives first: of the workers that can go on, the one
furthest upstream goes first. Windows are Pekko's: one every `within` from the
first pull, and a full group starts the next from the instant it was emitted.
A tick that falls due while nothing is asking is dropped, as Pekko's is.
`restartOnDefect` waits its schedule's delay on the test's clock and logs at
the test's time. Stage bodies read the test's clock as lark's `clock`.
`running.emitted()` reads what reached the end so far.

Decided while moving the petshop relay onto it (2026-09-25), for editing:
`mapPar` runs on `TestStreams` one element at a time, in the order they came,
as "Not doing" says it should. The relay's blocking ask is a `mapPar`, and a
test of its timing wants no second element in flight. `flatMapMerge` is still
refused: nothing has needed it yet.
