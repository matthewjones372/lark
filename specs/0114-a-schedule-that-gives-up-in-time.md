# 0114 — A schedule that gives up in time

## Problem

A test of something eventually consistent waits for it: an outbox to drain, a
projection to catch up. Kotest's `eventually` is `suspend`, and the block of
`Module.use` and `testApp` is not, so a caller reaches for `runBlocking` or
writes a polling loop of their own. The petshop's end-to-end test did both
before landing on `Schedule.retry`:

```kotlin
val patiently = Schedule.spaced<Throwable>(20.milliseconds) zipLeft Schedule.recurs(250)
patiently.retry { database.unsent() shouldBe 0L }
```

That already works and rethrows the last assertion, but nothing in it says five
seconds. It counts tries, and each try's own time is added to the wait, so it
gave up after twelve seconds when every try was a SQL query. A schedule can
stop after `n` tries and cannot stop after a duration.

## Not doing

- A `suspend` anything. `lark-app` stays without coroutines (docs/app.md, "No
  effect type").
- An `eventually` of lark's own in this spec. It is an open question below.
- Changing `retry`, `recurs` or `spaced`.

## Shape

```kotlin
val patiently = Schedule.spaced<Throwable>(20.milliseconds) zipLeft Schedule.upTo(5.seconds)
patiently.retry { database.unsent() shouldBe 0L }
```

`Schedule.upTo(duration)` continues with no delay of its own, answering the
time elapsed so far, and is done once that reaches `duration`. Elapsed is read
from `clock.get().now()`. Its starting point is the first `step`, which is the
first failure, not the first try. Combined with `zipLeft`/`zipRight`, it stops
whichever schedule it is paired with.

## Why this shape

One combinator gives every schedule a deadline, retries in production code
included, and the test case is just one use of it. The alternative is
`eventually(timeout) { }` in a `lark-test` module, which reads better in a test
and hides the schedule. Recommended: `upTo` first. An `eventually` would be a
one-line wrapper over it, if one is wanted later.

Reading the inherited clock rather than `System.nanoTime` means a `TestClock`
drives it. `adjust(5.seconds)` ends it without anything sleeping, the way
`spaced` already behaves.

## Stack

- [ ] **`spec-0114-up-to`** — `Schedule.upTo(duration)` and its tests.
      Done when: on a `TestClock`, `spaced(1.seconds) zipLeft upTo(5.seconds)`
      retries a failing action and gives up once the clock has moved five
      seconds, rethrowing the last failure; on `fixedClock` it never gives up
      by itself, which the test states.

## Acceptance

```bash
./gradlew :lark:test --tests '*Schedule*'
./gradlew build
```

## Open questions

- **Name?** `upTo`, `during` (ZIO's `recurUpTo`/`during`) or `forAtMost`.
  Recommended: `upTo`, which reads well after `zipLeft`.
- **Measured from the first try or the first failure?** `step` is first called
  on the first failure, so measuring from the first try needs `retry` to be
  told. Recommended: the first failure, documented as such.
- **`fixedClock` never moves, so `upTo` never ends on it.** Is that acceptable,
  given that `fixedClock` is "what most tests of a backoff want"? Recommended:
  yes, and say so in the KDoc, because `recurs` is the bound for that clock.
- **Also ship `eventually`?** Recommended: not until a second caller wants it.
