# 0209 — A flock nothing can raise in

## Problem

`flock { }` returns `Either<E, A>`, and Kotlin cannot infer `E` from a block that never raises. A flock held for
its forks, actors or close hooks rather than for a typed error has to name both type parameters, and then unwrap an
`Either` that can never be a `Left`:

```kotlin
val closed = flock<Nothing, Flock<Nothing>> {
    spawn("postbox", postbox())
    this
}.getOrNull()!!
```

The repo has 175 `flock<Nothing, …>` calls: the app's actors, the JUnit engine, the bank example, the benchmarks,
the actor and cluster guides, and most of the actor, remote and cluster suites. Their tests assert
`shouldBeRight x.right()` or call `getOrNull()` only to get past the `Either`.

## Not doing

- **No change to `flock`, `Fail<E>.flock` or `Flock<E>`.** A flock that raises a typed error keeps returning `Either`.
- **No split of `Flock` into a scope type and a `Fail`.** Every actor, cluster and stream extension is written on
  `Flock<F>`, so the split breaks all of them for what this spec gets with one function.
- **No typealias for `Flock<Nothing>`.** See open question 2.

## Shape

```kotlin
/** A flock nothing in [block] can raise in, so it answers [block]'s value: only a throw leaves it early. */
fun <A> scope(on: Executor = VirtualThreads, block: Flock<Nothing>.() -> A): A = Unraisable.flock(on, block)

val closed = scope {
    spawn("postbox", postbox())
    this
}
```

- It closes exactly as `flock` does: hooks run, then forks are interrupted and joined, and a fork that threw and
  was never awaited rethrows from `scope`.
- **The swap:** every `flock<Nothing, …> { }` becomes `scope { }`, in main sources, docs and tests. Each assertion
  that unwrapped the `Either` now asserts on the value.
- **Docs:** `docs/core.md` names `scope { }` next to `flock { }`, with `page` as its example.

## Why this shape

`Unraisable.flock` already is this function, and the top-level combinators already fork under it. Making it public
under a name costs one line and no new behaviour.

**Alternative:** overload `flock` with a one-type-parameter version that returns `A`. A call with no type arguments
would then be ambiguous, or would quietly pick a different return type. Recommended: a separate name.

## Stack

- [ ] **`claude/flock-nothing-design-1ix8zy`** — `scope` in `Flock.kt`, with a test, and every `flock<Nothing, …>` in
      the repo swapped for it.
      Done when: `:surefold:check` passes with a test that `scope` answers its block's value and rethrows an
      unawaited fork's throw, and `grep -rn 'flock<Nothing' --include=*.kt --include=*.md . | grep -v '^./specs'`
      finds nothing.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26).

1. ~~What is it called?~~ `scope`. `flock` overloaded is ambiguous (see above). A name with `flock` in it
   (`flockOf`, `unraisedFlock`) reads as a second kind of flock, when it is the same flock with no error to raise.
2. ~~A typealias, `typealias Scope = Flock<Nothing>`, for the signatures that hold one?~~ No. It gives one type a
   second name in every signature that holds one, and `Flock<Nothing>` already says what it is.
3. ~~Does the swap land in the same PR as the function?~~ Yes. Main sources and docs come to about 30 lines. The
   ~145 test sites don't count toward the cap, and leaving them on `flock<Nothing, …>` would keep the style this
   spec retires.
