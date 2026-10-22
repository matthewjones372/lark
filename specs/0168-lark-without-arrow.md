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
- **No reimplementation of Arrow beyond what Lark itself uses.** That is
  `Raise`, `either { }`, `bind`, `ensure`, `recover`, `Either`, `left` and
  `right`, plus `nonFatalOrThrow`. There is no `Option`, no `Ior`, no
  `NonEmptyList`, and no optics.
- **No behaviour change.** Cancellation, interrupts and the order of failures
  stay exactly as they are today. Only the types move.

## Shape

`lark` owns the minimal error model in `io.github.matthewjones372.lark.raise`.
The few `Option` and `NonEmptyList` uses become `A?` and `List<A>` with a
non-empty check:

```kotlin
// lark, after: same code, different import
import io.github.matthewjones372.lark.raise.either

fun dashboard(id: Id): Either<Err, Dashboard> = either {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}

// lark-arrow: for a codebase already on Arrow
import io.github.matthewjones372.lark.arrow.*

val a: arrow.core.Either<Err, Dashboard> = dashboard(id).toArrow()
arrow.core.raise.either { lark { parZip({ find(id) }, { orders(id) }) { u, o -> u to o } } }
```

The `lark { }` call bridges an Arrow `Raise<E>` into a Lark `Raise<E>`.

## Why this shape

Lark keeps `Either` and typed errors, but it uses only a sliver of Arrow, so
it owns that sliver: about 150 lines of `Raise`, `either`, `Either` and
`nonFatalOrThrow`. Call sites change only their import, and `lark-arrow`
covers the codebases that want the rest of Arrow. `kotlin.Result` was
rejected (maintainer, 2026-10-22) because it loses `E`.

## Stack

- [ ] **`raise-in-lark`** — `Raise`, `either`, `Either` and `nonFatalOrThrow`
      in `lark`, tested alone. Arrow is still on the classpath.
      Done when: the tests for these pass without importing `arrow`.
- [ ] **`lark-arrow`** — a new module with `toArrow()`, `toLark()` and the
      `lark { }` bridge, which adds `arrow-core`.
      Done when: its round-trip and bridge tests pass.
- [ ] **`core-off-arrow`** — `core/lark` and `lark-structured` move to the
      Lark types, and `arrow-core` leaves `lark`'s build.
      Done when: `NoOtherDependenciesTest` allows only the JDK and the stdlib.
- [ ] **`stream-off-arrow`** — the four `stream/` modules move.
- [ ] **`actor-off-arrow`** — `lark-actor`, `-journal-jdbc` and `-remote`
      move.
- [ ] **`app-off-arrow`** — `lark-app`, `-typesafe` and `lark-bank` move.
- [ ] **`kafka-off-arrow`** — `lark-kafka` and `lark-kafka-pekko` move.
- [ ] **`docs-off-arrow`** — the README, `docs/`, `llms.txt`, the SKILL and
      AGENTS.md move, with a migration page that points to `lark-arrow`.

The test migrations ride along with each entry and don't count toward the
cap. If an entry exceeds 200 lines it gets split by module.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
grep -rl 'import arrow' --include=*.kt . | grep -v lark-arrow   # empty
```

## Open questions

1. ~~Toolchain, or Lark-owned types?~~ Answered (2026-10-22): keep `Either`
   and `Raise` as Lark-owned types, and don't use `kotlin.Result`. Lark needs
   about 10% of Arrow.
2. ~~Should `raceN` keep returning `Either<A, B>`?~~ Answered: yes, Lark's
   own `Either`.
3. ~~Should Lark's `Either` keep Arrow's names?~~ Answered: yes, but only
   the members Lark's code and docs call today.
4. ~~Is this a breaking release with no deprecation period?~~ Answered: yes.
   `lark-arrow` is the migration path.
