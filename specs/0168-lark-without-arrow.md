# 0168 — Lark without Arrow

## Problem

`lark` declares `api("io.arrow-kt:arrow-core:2.1.2")`, so every user takes on
Arrow even when they don't use it. Every public combinator takes `Raise<E>`
from Arrow as its receiver, and `raceN` returns Arrow's `Either`. Arrow is
imported by 51 main files across 14 modules, and by 73 test files. Users who
don't use Arrow pay for a library they never call, and Lark's API changes
whenever Arrow's does. The maintainer's direction (chat, 2026-10-22): drop
Arrow but keep `Either` and typed errors. Lark needs about 10% of Arrow, so
it owns that part, and Arrow users get a module that converts.

## Not doing

- **No coroutine or `suspend` interop.** That is unchanged from 0003.
- **No Arrow code, copied or ported.** Lark builds only what it uses:
  failing with an error, `bind`, `ensure`, `recover`, `Either` and
  `nonFatalOrThrow`, plus `NonEmptyList`. There is no `Option`, no `Ior`, no
  error accumulation beyond what Lark already has, and no optics.
- **No rich-errors support in this spec.** The maintainer wants it
  (2026-10-22), but the compiler feature is experimental. It gets its own
  spec once rich errors are usable on the build's Kotlin: `attempt` gains an
  `A | E` form, plus conversion to and from `Either`. Here, `Fail<E>`
  leaves `E` unbounded, so an `error class` can be used for `E` later.
- **No change to concurrency behaviour.** Cancellation, interrupts and the
  order of failures stay as they are. The only behaviour changes are the
  three improvements under Shape.

## Shape

`lark` owns its error model in `io.github.matthewjones372.lark.fail`, written
from scratch. It takes Arrow's ideas but none of Arrow's code, and it is
credited as such in the README. `Either`, `Left` and `Right` are the common
vocabulary from Haskell and Scala, so they keep those names. The parts that
are Arrow's own design get Lark's own names: `Fail<E>`, `fail(e)` and
`attempt { }`.

```kotlin
import io.github.matthewjones372.lark.fail.*

context(_: Fail<Err>)
fun dashboard(id: Id): Dashboard =
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }

val d: Either<Err, Dashboard> = attempt { dashboard(id) }

// lark-arrow: for a codebase already on Arrow
val a: arrow.core.Either<Err, Dashboard> = attempt { dashboard(id) }.toArrow()
arrow.core.raise.either { lark { dashboard(id) } }  // Arrow Raise<E> -> Lark Fail<E>
```

The design improves on Arrow in three places:

1. **Context parameters, not extension receivers.** A fallible function
   needs a `Fail` from its caller (`context(_: Fail<E>)`) instead of being
   written as an extension on it. Two error types then sit side by side
   without nesting receivers.
2. **`catch (e: Exception)` can't swallow a failure.** Lark's internal
   signal extends `Throwable` directly, with no stack trace, so a broad
   `catch` in user code no longer eats a raise. `nonFatalOrThrow` rethrows
   it.
3. **A leaked scope names its origin.** Calling `fail` after `attempt` has
   returned, or from a thread the scope didn't fork, throws an error that
   points at the line where the scope was opened, in the style of spec
   0119.

The few `Option` uses become `A?`. `NonEmptyList` stays, as Lark's own
(maintainer, 2026-10-22), because `validate()`, `reading` and
`parZipOrAccumulate` promise it in their signatures. It is a read-only
`List` with `head`, `tail`, `map`, `plus`, `nonEmptyListOf` and
`toNonEmptyListOrNull`.

## Why this shape

Lark uses only a sliver of Arrow, so it owns that sliver: about 200 lines,
written fresh. `kotlin.Result` was rejected (maintainer, 2026-10-22) because
it loses `E`. Keeping Arrow's names would have made the move only an import
swap, but would have read as a copy of Arrow's API under another name.
Lark's own names and an open credit avoid that, and `lark-arrow` keeps the
path easy for anyone already on Arrow.

## Stack

- [ ] **`fail-in-lark`** — `Fail`, `attempt`, `Either`, `NonEmptyList` and
      `nonFatalOrThrow` in `lark`, tested alone, with the three improvements. Arrow is still on the classpath.
      Done when: the tests for these pass without importing `arrow`.
- [ ] **`lark-arrow`** — a new module with `toArrow()`, `toLark()` and the
      `lark { }` bridge from `Raise` to `Fail`, which adds `arrow-core`.
      Done when: its round-trip and bridge tests pass.
- [ ] **`flock-on-fail`** — `Flock`, `Flight`, `Fork`, `Race`, `Timeout`,
      `Retry` and `Policy` move to `Fail`, and a fail from a thread the scope
      didn't fork is rejected. The Arrow-`Raise` versions stay as one-line
      wrappers, so the modules above `lark` keep compiling.
      Done when: core's tests pass on `Fail`, and every other module builds
      unchanged.
- [ ] **`par-on-fail`** — `parZip` and `parMap` at every arity, with
      wrappers.
- [ ] **`accumulate-on-fail`** — `parZipOrAccumulate` and
      `parMapOrAccumulate`, with wrappers. Split by arity if it runs past the
      cap.
- [ ] **`structured-on-fail`** — `lark-structured`.
- [ ] **`policy-on-fail`** — `retry`, `retryRaise`, `guarded`, `Guard`,
      `raceN` and `timeout` take a `Fail`, and `raceN` and `Schedule.andThen`
      answer with Lark's `Either`. `flock-on-fail` moved only how these treat a
      failure; the modules below call them, so they move first (added
      2026-10-23).
      Done when: core's tests and the guide examples pass with none of these
      taking a `Raise`.
- [ ] **`stream-off-arrow`** — the four `stream/` modules move.
- [ ] **`actor-off-arrow`** — `lark-actor`, `-journal-jdbc` and `-remote`
      move.
- [ ] **`app-off-arrow`** — `lark-app`, `-typesafe` and `lark-bank` move. `lark-bank`
      moved with `actor-off-arrow` (2026-10-23), since it reads the actors' answers.
- [ ] **`kafka-off-arrow`** — `lark-kafka` and `lark-kafka-pekko` move. Folded into
      `stream-off-arrow` (2026-10-23): their records' `Either`s flow through the stream
      operators, so the two cannot move apart.
- [ ] **`core-drops-arrow`** — the wrappers go, and `arrow-core` leaves
      `lark`'s build.
      Done when: `NoOtherDependenciesTest` allows only the JDK and the stdlib.
- [ ] **`docs-off-arrow`** — the README, `docs/`, `llms.txt`, the SKILL and
      AGENTS.md move, with a migration page that points to `lark-arrow`.
- [ ] **`either-catch`** — `Either.catch { }` in `lark.fail`, which keeps a
      fatal throw and a `fail` going up through `nonFatalOrThrow`. `lark-kafka`
      drops its own `catching` for it. Missed by the entries above (maintainer,
      2026-10-24).

The test migrations ride along with each entry and don't count toward the
cap. The wrappers (maintainer, 2026-10-22) exist so that no entry has to
move core and every module above it at once. If an entry exceeds 200 lines it gets split by module.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
grep -rl 'import arrow' --include=*.kt . | grep -v lark-arrow   # empty
```

## Open questions

1. ~~Toolchain, or Lark-owned types?~~ Answered (2026-10-22): keep `Either`
   and typed errors as Lark-owned types, and don't use `kotlin.Result`. Lark needs
   about 10% of Arrow.
2. ~~Should `raceN` keep returning `Either<A, B>`?~~ Answered: yes, Lark's
   own `Either`.
3. ~~Should Lark's `Either` keep Arrow's names?~~ Revised (2026-10-22):
   keep `Either`, `Left` and `Right`, but write everything from scratch and
   give Arrow's own design Lark's names.
4. ~~Is this a breaking release with no deprecation period?~~ Answered: yes.
   `lark-arrow` is the migration path.
5. ~~Are `Fail`, `fail` and `attempt` the right names?~~ Answered
   (2026-10-22): yes.
6. ~~Should Lark use context parameters?~~ Answered (2026-10-22): yes, it
   follows where Kotlin is going. If 2.4.10 still needs
   `-Xcontext-parameters`, `fail-in-lark` turns it on in the root build for
   every module, not module by module.
7. ~~Where does the credit to Arrow go?~~ Answered (2026-10-22): a README
   line saying the error model is inspired by Arrow. No NOTICE file, since
   no code is copied.
