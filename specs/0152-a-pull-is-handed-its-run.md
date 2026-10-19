# 0152: a pull is handed its run

## Problem

`Node.pull()` (`Pull.kt:69`) reads its run from four ThreadLocals:

| ThreadLocal | Read where | What it gives |
|---|---|---|
| `Boundaries.here()` | `Pull.kt:70` | a backend that runs some operators its own way |
| `Turns.taking()` / `Turns.here()` | `Pull.kt:137-150`, `:403`, `:573` | a test's clock, or none |
| `Releases.here()` | `Pull.kt:137-150`, `:404`, `:576` | the run's forks to release |
| `Resources.here()` | `Pull.kt:81`, `Timed.kt:205` | the run's blocking sources to wake and close |

The first problem is that the dependency is hidden. A caller can't tell what
`pull()` needs, and learns only when a `checkNotNull` fails at runtime ("a
node with time in it was pulled off a test's clock").

The second is that every fork has to re-bind the ThreadLocals by hand, and
forgetting one fails silently. Spec 0150 fixes the instance found so far,
where `Resources` never reached a fork.

There is also repetition. Five operators pick their implementation by asking
`Turns.taking()` in their own branch: `MapPar`, `Buffer`, `MapAsync`, `Merge`
and `Conflate`, plus `FlatMap` and `timeline()`. And `pullsOnClock()` is just
`pulls()` (`Pull.kt:63`).

Research for this spec found that nothing outside lark-stream-forks reads the
four ThreadLocals, and user lambdas never call `pull()`. Spec 0066 made only
`Boundaries` ambient, to keep spawning on the thread that steps. Its own
thread check in `ActorBoundaries.kt:41` already enforces that.

## Not doing

- The clock binding in `Turns.fork` (`clock.locally`, `Waiting.within`).
  User lambdas read lark's `clock` through it, and specs 0017 and 0018 chose
  a ThreadLocal on purpose.
- The `Boundaries.pull(node, pulled)` interface (`@StreamSpi`). `pulled`
  closes over the context, so lark-stream-actors' implementation doesn't
  change.
- Behaviour, apart from the late-built pulls below.

## Shape

```kotlin
internal class PullContext(
    val timeline: Timeline,            // Turns on a test's clock, RealTime on Forks
    val releases: Releases?,
    val resources: Resources?,
    val boundaries: Boundaries?,
) {
    val onClock: Boolean get() = timeline is Turns
}

internal fun Node.pull(ctx: PullContext): Pull =
    ctx.boundaries?.takeUnless { ctx.onClock }?.pull(this) { it.pull(ctx) } ?: when (this) {
        is Node.Blocking -> blocking(ctx.resources)
        is Node.Buffer -> if (ctx.onClock) bufferedOnClock(ctx.timeline as Turns) else buffered(ctx)
        // …
    }
```

- `Pulling` builds the context once per run (`Forks.kt:179-180`), and so does
  `ForksOnClock`.
- A fork is handed the `ctx` it was built under and uses it as it is. The
  `Bound` from 0150 is deleted.
- `catchAll`, `flatMapConcat`, `concat` and `restartOnDefect` build their late
  pulls from the `ctx` they were built with, not from the thread that later
  pulls them.

## Why this shape

Passing the context as a parameter makes `pull`'s dependencies part of its
interface, so a missing one fails to compile instead of failing at runtime or
not at all. The other option is keeping the ThreadLocals and adding a check
that each fork re-binds all of them. That catches the bug class but leaves the
interface hidden, so I recommend the parameter.

## Stack

- [ ] **`spec-0152-context`**: `PullContext`, built by both runs and passed to
      every `pull`. The ThreadLocals are still set but no longer read.
      Done when: lark-stream-forks, lark-stream-actors and parity tests pass,
      and `Pull.kt` has no `here()` or `taking()` call.
- [ ] **`spec-0152-forks`**: forks carry `ctx`. `Bound` and
      `pullsOnClock` are deleted.
      Done when: no fork calls `Releases.within` or `Resources.around`.
- [ ] **`spec-0152-unbind`**: the four ThreadLocals are deleted.
      `ActorFanin`'s `run.within { … }` becomes a call that takes the
      context.
      Done when: `grep ThreadLocal lark-stream-forks` finds only `Turns`'s
      clock binding.

## Acceptance

```bash
./gradlew :lark-stream-forks:test :lark-stream-actors:test :lark-stream-parity:test
```

## Open questions

1. `Boundaries.within` is public (`@StreamSpi`). After entry 3 nothing calls
   it. Should it be deleted, or deprecated for one release? I recommend
   deleting it: it is pre-1.0 SPI, and lark-stream-actors is its only user.
2. Late-built pulls change behaviour: today they use whatever is bound on the
   thread that pulls them. Is there any case where that differs from the
   `ctx` they were built with, other than the 0150 bug? I recommend running
   `DifferentialTest` with a seed sweep in entry 1 to find out.
3. Does entry 1 stay under 200 lines? It threads `ctx` through about 45 call
   sites. If not, I recommend splitting it by file: `Pull.kt` first, then the
   fork files.
