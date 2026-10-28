# 0245 — A list traversed to one Either

## Problem

Running a failing function over a list, stopping at the first failure, is `attempt { xs.map { f(it) } }`. It is
correct, but nobody coming from ZIO (`ZIO.foreach`) or Arrow (`traverse`, `mapOrAccumulate`) looks for it there: the
guide never shows it, and the nearest named functions, `parMap` and `mapOrAccumulate`, are both something else. Users
ask what the traversal is called, and the answer is "there isn't one, use `map`".

## Not doing

- No `bind()` on `Iterable<Either<E, A>>`. `ids.map(::load).bind()` reads like a traversal but runs every `load`
  before looking at any result, so it never stops early.
- No `sequence` for a list of `Either`s already computed; see question 2.
- No traversal over `Map`, `Sequence` or `Array`; `Iterable` only.
- No change to `parMap`, `mapOrAccumulate` or `attempt`; `parMap` keeps its unbounded form.

## Shape

<!-- sketch -->
```kotlin
import io.github.matthewjones372.surefold.Limit
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.bind
import io.github.matthewjones372.surefold.fail.traverse
import io.github.matthewjones372.surefold.parTraverse

fun loadAll(ids: List<Id>): Either<NotFound, List<User>> = ids.traverse { id -> load(id).bind() }

fun loadAllAtOnce(ids: List<Id>): Either<NotFound, List<User>> = ids.parTraverse { id -> load(id).bind() }

val db = Limit.adaptive("db")   // one per dependency, shared by every caller

fun loadAllFromDb(ids: List<Id>): Either<NotFound, List<User>> = ids.parTraverse(db) { id -> load(id).bind() }
```

- `fun <E, A, B> Iterable<A>.traverse(f: Fail<E>.(A) -> B): Either<E, List<B>>` in `fail/`, beside `attempt`: runs
  `f` on each element in order on the calling thread; the first failure ends it and no later element runs.
- `fun <E, A, B> Iterable<A>.parTraverse(limit: Limit = Limit.fixed(PAR_TRAVERSE_DEFAULT, "parTraverse"),
  f: Fail<E>.(A) -> B): Either<E, List<B>>` in `Traverse.kt` (`Par.kt` is at detekt's 40-function cap): `attempt { parMap(this, limit, …) }`, so the first failure
  interrupts the rest exactly as `parMap` does, and no fork starts after one has failed.
- **Bounded by default.** With no `limit`, at most `PAR_TRAVERSE_DEFAULT` (64) elements run at once, so a list of a
  million ids parks 64 forks, not a million. Passing a `Limit` overrides it: a fixed one for a different number, or a
  dependency's shared `Limit.adaptive(…)` so the traversal counts against that dependency's budget. There is no
  unbounded form; `parMap` without a limit stays for that.
- A throw is not a failure: it leaves `traverse` unchanged, as it leaves `attempt`.
- `docs/core.md` gains "Traversing a list" before "Collecting every error": the two functions, and a table of the
  ZIO names (`foreach`, `foreachPar`, `foreachDiscard`, `validate`) against Surefold's.

## Why this shape

`traverse` is one line over `attempt` and `map`; what it buys is a name people search for and a place in the guide.
**Alternative A:** only the guide section, no new function — costs nothing, but the question it answers is "what is
it called", and the answer would still be "nothing". **Alternative B:** `forEach`, ZIO's name — clashes with Kotlin's
`Iterable.forEach`, which returns `Unit`. Recommended: `traverse`, Arrow's and Cats' name, plus the guide section.

## Stack

- [x] **`spec-0245-traverse`** ([#874](https://github.com/matthewjones372/surefold/pull/874)) — `traverse`, `parTraverse` (bounded by default, a `Limit` to override), and the guide section with
      a compiled fence (`<!-- core-traverse -->`).
      Done when: a failure at element 2 of 4 answers `Left` and elements 3 and 4 never ran; every element ran on the
      calling thread (`Thread.currentThread()` read in each); an empty list answers `Right(emptyList())`;
      `parTraverse` with a failing element interrupts a sibling blocked in a sleep; with no `limit`, 200 elements
      never have more than 64 inside `f` at once (a counter read in each), and with `Limit.fixed(2)` never more than
      2; the fence compiles under `CoreGuideTest`.

## Acceptance

```bash
./gradlew :surefold:test
```

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Name:** `traverse`, `mapOrFail`, or `attemptEach`? **Answered: `traverse`** (maintainer, 2026-10-28). Recommend `traverse`: it is the name both ZIO users (via
   Cats) and Arrow users already know, and Surefold's `parZip`/`parMap` already borrow Arrow's vocabulary.
2. **`sequence`** for an `Iterable<Either<E, A>>` already computed? **Deferred** (maintainer, 2026-10-28). Recommend deferred: its only honest use is on
   results that already exist, and it invites the eager `map(::load)` mistake above.
3. **A `Fail` receiver form** too, `Fail<E>.traverse(xs, f): List<B>`, for use inside a scope? **Answered: no** (maintainer, 2026-10-28). Recommend no: inside a
   scope, Kotlin's `map` already is that function.
4. **`parTraverse` name:** keep it, or have users write `attempt { parMap(…) }`? **Answered: keep** (maintainer, 2026-10-28). Recommend keep: without it,
   `traverse` would be the one traversal with no parallel twin, which `parZip`/`parMap` have taught users to expect.
5. **The default bound:** `Limit.fixed(64)`, `Limit.fixed(availableProcessors)` as `Suite` uses, or a fresh
   `Limit.adaptive` per call? **Answered: fixed 64, overridable** (maintainer, 2026-10-28). Recommend fixed 64: traversals are mostly I/O on virtual threads, where a CPU count is
   too low; an adaptive limit made per call starts from nothing each time and guards no shared dependency, so
   adaptive belongs in the override, not the default. A public `PAR_TRAVERSE_DEFAULT` makes the number visible.
