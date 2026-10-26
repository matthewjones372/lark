# 0210 — One name for a scope

## Problem

After spec 0209 the same idea has two names. The type is `Flock<E>`, a typed scope is opened with `flock { }`, and
one nothing can raise in is opened with `scope { }`. `docs/core.md` already calls a flock "the scope that `async`
forks in", and every other structured-concurrency library calls this thing a scope. The word "flock" has to be
learned, and it buys nothing over "scope".

There are about 280 lines across 20 modules of main sources and docs, plus about 190 test lines. Most of them are
extensions written on `Flock<F>` (`spawn`, `watch`, `cluster`, `node`, `topic`) and the prose around them ("the
flock's clock", "a flock held open").

## Not doing

- **No change in behaviour.** Opening, forking, close hooks and close order stay exactly as they are.
- **No rename of the internals** that hold a scope's forks (`Nest`, `Flight`, `Fork`): they are private names.
- **No rewrite of shipped specs.** They are the record and keep saying "flock".

## Shape

```kotlin
interface Scope<E> : Fail<E> { /* on, async, onClose: unchanged */ }

fun <A> scope(on: Executor = VirtualThreads, block: Scope<Nothing>.() -> A): A        // from 0209
fun <E, A> Fail<E>.scope(on: Executor = VirtualThreads, block: Scope<E>.() -> A): A   // was Fail<E>.flock

// A typed scope is the nested form inside attempt, which docs/core.md already shows:
val report: Either<Err, Dashboard> = attempt { scope { /* async, fail */ } }

fun <F, M : Any, S, E> Scope<F>.spawn(/* … */): ActorRef<M>                          // was Flock<F>.spawn
```

- **The top-level `flock<E, A> { }` goes.** It has about 56 callers, nearly all of them in `FlockTest`. Each one
  becomes `attempt { scope { } }`.
- **Inside a `Fail`, `scope { }` resolves to the extension.** Kotlin tries implicit-receiver extensions before
  top-level functions, so inside `attempt { }` (or inside another scope) `scope` raises into the enclosing `Fail`,
  as `Fail<E>.flock` does today. A test pins this.
- **Prose and names follow:** KDoc, `docs/`, `README.md` and `GLOSSARY.md` say "scope". `FlockTest` becomes
  `ScopeTest`, the `Flocked` benchmark base becomes `Scoped`, and `heldFlock` becomes `heldScope`.

## Why this shape

One word for one idea, and it is the word readers already know. Dropping the top-level `Either` form leaves a
single way to get a typed scope, which is already the documented way.

**Alternative:** keep a typed top-level entry under a new name, such as `scoped<E, A> { }`. It saves `attempt { }` at
56 call sites but brings back two names. Recommended: drop it.

## Stack

- [ ] **`spec-0210-core`** — `Scope`, `Fail<E>.scope` and the top-level form dropped, in `surefold`, with `ScopeTest`
      and the resolution test. `docs/core.md` and `GLOSSARY.md` follow. Every module still compiles against
      `typealias Flock<E> = Scope<E>`, kept for this entry only.
      Done when: `:surefold:check` passes and `ScopeTest` covers `attempt { scope { fail(e) } }` answering `Left(e)`.
- [ ] **`spec-0210-actors`** — `surefold-actor`, `-actor-remote`, `-stream-actors` and their docs move to `Scope`.
      Done when: their `check` passes and `grep -rnw Flock` finds nothing in them.
- [ ] **`spec-0210-rest`** — cluster, app, benches, the example and `README.md`. The typealias goes.
      Done when: `./gradlew build` passes and `grep -rnwi 'flock' --include=*.kt --include=*.md . | grep -v '^./specs'`
      finds nothing.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
grep -rnwi 'flock' --include=*.kt --include=*.md . | grep -v '^./specs'   # nothing
```

## Open questions

1. **A deprecated `Flock` typealias and `flock` function for one release, for code outside this repo?**
   Recommended: no. The version comes from SCM tags and the repo has made breaking renames before. A typealias only
   lives inside the stack, to keep each entry compiling.
2. **Does the overload resolution hold?** If the top-level `scope` and `Fail<E>.scope` turn out ambiguous in some
   position, the fallback is to name the nested form `Fail<E>.scoped { }`. Recommended: try it first; the
   resolution test in the first entry answers it.
3. **Error messages that say "flock"?** Three in `actor/Runtime.kt` (for example, "this flock has closed, and its
   actors with it"). No metric or log key uses the word. Recommended: reword them to "scope". A test that matches on
   the text changes with them.
