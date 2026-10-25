# 0150: a blocking source on a fork belongs to its run

## Problem

A `Stream.blocking` source registers itself with its run's `Resources` when its
pull is built (`Pull.kt:81`, `blocking(Resources.here())`). That registration
is what lets `stop()` wake a read that is blocked, and lets the run close the
resource when it ends (`BlockingTest:56`).

`Resources.here()` is a ThreadLocal. `Forks.within` binds it on the run's
thread (`Forks.kt:201`). A fork re-binds `Releases` but not `Resources`. This
happens in six places: `Merged.kt:30`, `Merged.kt:91`, `Buffered.kt:25`,
`Conflated.kt:51`, `Awaited.kt:34` and `Window.kt:150`. On a test's clock,
`Turns.fork` (`Turns.kt:51-58`) re-binds neither: a `flatMapMerge` inner
(`Merged.kt:152`) or a `conflate` upstream (`Conflated.kt:71`) is pulled
there.

So any pull built on a fork thread sees no resources, for example:

- each inner stream of `flatMapMerge` (`Merged.kt:94`);
- the next stream of a `concat`, the inner of `flatMapConcat`, or the
  recovery of `catchAll`, when one of those sits upstream of a `buffer`,
  `merge`, `mapAsync` or `conflate`;
- a `restartOnDefect` built there, which then gets no attempt scope
  (`Timed.kt:205`).

A blocking source in any of these places is never registered. Stopping the run
does not wake its read, and the run does not close it. If the read blocks, its
fork hangs and whatever the resource holds stays held, for example a Kafka
consumer's place in its group. This was found by reading the code and has not
been seen in a test.

## Not doing

- Replacing the ThreadLocals with an explicit context. That is spec 0152,
  which builds on this one.
- Changing how `Resources` closes resources, or the order it closes them in.

## Shape

```kotlin
// Window.kt or Resources.kt: what a fork carries from the thread that started it
internal class Bound(val releases: Releases?, val resources: Resources?) {
    fun <T> within(body: () -> T): T =
        (resources?.let { r -> { r.around(body) } } ?: body).let { Releases.within(releases, it) }

    companion object {
        fun here() = Bound(Releases.here(), Resources.here())
    }
}
```

Each of the six forks, and `Turns.fork`, captures a `Bound` where today it captures `releases`,
and runs inside `bound.within { … }`.

## Why this shape

The fix fits each site the way the code is already written: each site already
captures one value and re-binds it, and capturing both instead is a one-line
change per site. Re-binding `Resources` alone at each site would leave the next
ThreadLocal someone adds open to the same gap. Spec 0152 removes the pattern
altogether. This spec closes the bug first, so the fix does not wait behind
that refactor.

## Stack

- [x] **`spec-0150-bound`** ([#615](https://github.com/matthewjones372/lark/pull/615)): `Bound`, used at all six forks and in
      `Turns.fork`. The failing
      tests come first:
      - stopping a run wakes a blocked `Stream.blocking` read inside
        `flatMapMerge`, and closes the resource once;
      - the same for one behind `concat` upstream of `buffer`;
      - the first case again on `ForksOnClock`.
      Done when: all three pass, and no fork calls `Releases.within` directly.

## Acceptance

```bash
./gradlew :lark-stream-forks:test :lark-stream-parity:test
```

## Open questions

Answered (maintainer, 2026-10-25: the recommendations stand).

1. ~~Does the Pekko backend have the same gap?~~ No: `BlockingOnPekkoSuite` stops a source blocked inside
   `flatMapMerge` and finds it woken and closed once, with no change to Pekko.
2. ~~Should a pull built with no `Resources` fail loudly?~~ Yes, but in spec 0152, once the context is passed in
   and no pull reads `Resources` from its thread.
