# 0006 — A stream in the Raise DSL

## Problem

`lark-stream`'s element functions run in dipper's own `Failing<E>` scope with
`fail(e)`, not Arrow's `Raise<E>` — so `bind()`, `ensure` and every lark
combinator are out of reach inside `mapOrFail`. `mapAsync` takes a
`CompletionStage`, so a blocking body per element has no spelling. And a
handler that runs a stream gets `CompletionStage<Exit<E, R>>` back and folds
it by hand. Three seams; this closes them, so the whole of a service — handler,
fan-out, stream — is one `Raise` vocabulary.

## Not doing

- **No new stream operators** beyond `mapPar`. Windowing, grouping,
  throttling: Pekko's, through `toSource()`.
- **No change to how a failure travels** — still Pekko's failure channel in
  dipper's private wrapper, unwrapped by `run`.
- **No `Failing` removal.** It stays as the name; it gains a supertype.

## Shape

```kotlin
Stream.from(rows)
    .mapOrFail { row -> Customer(row.id, row.customer ?: raise(NoCustomer(row.id))) }   // a Raise<E>: raise, bind, ensure
    .mapPar(4) { customer -> ledger.settle(customer).await().bind() }                    // a virtual thread per element
    .runFold(0) { n, _ -> n + 1 }
    .run(system)
    .awaitExit()                                                                          // Done → R, Failed(e) → raise(e), Died → throw
```

- `Failing<E> : Raise<E>`; `fail(e)` stays as an alias of `raise(e)`.
- `Stream<E, A>.mapPar(parallelism: Int, on: Executor = virtual threads, f: Raise<E>.(A) -> B): Stream<E, B>`
  — `mapAsync` underneath, each element's body on a lark fork; a `raise` is
  the stream's declared failure, a throw is `Died`; order and parallelism are
  `mapAsync`'s; `B : Any`, so the completion that drops an element cannot be
  written.
- `Raise<E>.awaitExit()` on `CompletionStage<Exit<E, R>>`: `Done(r)` is `r`,
  `Failed(e)` raises, `Died(cause)` throws `cause`; the `when` has no `else`.
  An interrupt while waiting cancels the stage as `lark-pekko`'s `await()`
  does.

## Why this shape

`Failing` gaining a supertype rather than being replaced keeps every dipper
test and every caller compiling, and costs nothing: `Raise<E>` has one
abstract member and `Failing` already has it. `mapPar` over `mapAsync` rather
than a new stage keeps every Pekko guarantee dipper's README makes. `awaitExit`
as the one fold of `Exit` into `Raise` is where the two halves of the family
meet, and it is three lines.

## Stack

- [x] **`spec-0006-raise`** (`main`, 8384dce) — `Failing<E> : Raise<E>`.
      Done when: `bind()` and `parZip` compile and run inside `mapOrFail`, and
      dipper's suite passes unedited.
- [x] **`spec-0006-mappar`** (`main`, e6d7017) — `mapPar`, on lark's forks, `on:` honoured.
      Done when: a blocking body per element runs on a virtual thread, a
      `raise` fails the stream with its `E`, and order is preserved.
- [x] **`spec-0006-exit`** (`main`, c8b80be) — `awaitExit`.
      Done when: each `Exit` case reaches a `handledRaising` body the way the
      shape says, through an in-repo test.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Does `mapPar` depend on `lark-pekko`** (for `on:` from a dispatcher) or
   on `lark` alone? Recommended: `lark` alone — `on:` is a plain `Executor`;
   `lark-pekko` is where a dispatcher becomes one.
2. **`awaitExit` in `lark-stream` or `lark-pekko`?** Recommended:
   `lark-stream`; `Exit` is its type.

Decided while building (2026-09-03): `awaitExit` takes the stage as an
argument (`awaitExit(pipeline.run(system))`), since an extension on the stage
with a `Raise` receiver needs context parameters; `mapPar` has a
`Stream<Nothing, A>` twin as `mapOrFail` does, so a first `raise` can name
`E`; a body is cancelled by the run, at stream termination, not by Pekko;
`lark-stream` depends on `lark-pekko` so the wait is `await()`'s one code.
