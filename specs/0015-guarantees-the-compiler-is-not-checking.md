# 0015 — Guarantees the compiler is not checking

## Problem

The library's claim is that the types say what will and will not happen, and
`DoesNotCompileTest` holds two lines of it for real: an element is never null,
and a declared failure stays in the type until something handles it. Both stop
short, and in four places the guarantee is currently held by a comment or by
three call sites happening to be written correctly.

The exit is the one that reaches a caller. `runWith`'s `M`, `runFold`'s `R`,
`Exit.Done`'s `A` and `awaitExit`'s `R` are all unbounded, and `run` is a
`thenApply` — which is called with a null value quite happily. So a sink whose
materialised stage completes with `null` becomes `Exit.Done(null)`, and
`awaitExit` hands it back into a slot Kotlin believes is non-null. That is the
`fromStage` hazard of spec 0009 at the other end of the pipeline: a stage
authored in Java completes with `null` whatever its type argument says, and a
`Sink` is that stage. `fromStage` got a spec, a runtime guard and six entries
in detekt's ban list; the exit got the bound left off.

The other three are internal. `through` (`Pipe.kt:44`) picks `E` freely from
two star projections, which is the library's only fully unchecked type-level
escape and the road every failure-changing operator takes. `@UnsafeVariance`
sits on `Stream.source`, `Pipe.flow` and `Run.graph`, defended by a comment
arguing that operators only read. `divertLefts` casts to `Either.Right`
unchecked, on a side a predicate settled a line earlier.

## Not doing

- **No typed sinks.** Spec 0008 declined them — "a sink does not fail with
  `E`" — and reopening that is a bigger change than this one.
- **No `catchDefects`.** Spec 0010 already ruled: a defect is a bug, not a
  domain answer. A handler for one invites treating it as the other.
- **No change to `Exit`'s three cases**, and no new operators at all.

## Shape

```kotlin
// Today, with a sink whose stage completes with null:
val report: Report = either { awaitExit(rows.runWith(reportSink).run(system)) }
// Exit.Done(null), typed non-null, and the NPE lands somewhere else entirely.

// After, the guard fromStage already has, at the other end:
// [ERROR] lark-stream: run ended with null, built at ReportService.kt:88
```

- `M : Any` on `runWith`, `R : Any` on `runFold`, `A : Any` on `Exit.Done`,
  `R : Any` on `awaitExit`. Generics erase to `Object` either way, so
  `lark-stream.api` does not move and no binary compatibility is spent.
- The materialised stage through the private `checked()` already in
  `Stream.kt`, so a null exit is `Died(NullPointerException)` naming `run`,
  exactly as a null completion names `fromStage`.
- `through` replaced by two narrower internals with no star projection: one
  for the operator that declares nothing (`either`, whose result is honestly
  `Stream<Nothing, _>`), one that reads `E2` off the handler's own return type
  (`catchAll`, `orElse`).
- `internal val source: Source<out A, NotUsed>` tried in place of
  `@UnsafeVariance`, and kept only if the whole operator set still compiles.
- `divertLefts`'s cast becomes a `Flow.collect` over a `PFBuilder.match`, so
  the JVM checks the side the predicate chose.

## Why this shape

Each is a place where the guarantee rests on prose. The alternative for the
exit — document that a sink can lie and leave it — is what raw Pekko does,
and spec 0009 rejected it at the entrance; taking it at the exit would mean the
library guards the half of the pipeline that was easy. The three internal ones
buy a reader nothing today and buy the next operator everything: `through` is
sound because three call sites are right, and nothing makes a fourth one be.

## Stack

- [ ] **`spec-0015-exit-bound`** — the four `: Any` bounds and their
      `DoesNotCompileTest` fixtures. Done when: `runFold(null) { _, _ -> null }`
      is refused and the compiler's own words are asserted.
- [ ] **`spec-0015-exit-guard`** — `checked()` on the materialised stage.
      Done when: a sink completing with `null` is `Died` naming `run`, and
      not `Done(null)`.
- [ ] **`spec-0015-internals`** — the two narrow internals in place of
      `through`, and the `divertLefts` cast made checked. Done when: no star
      projection is left in `Pipe.kt` and the operator surface is unchanged.
- [ ] **`spec-0015-variance`** — the `out A` projection tried.
      Done when: the three `@UnsafeVariance` suppressions are gone, or this
      spec records why they cannot be.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **Is the bound enough on its own?** No — a Kotlin bound is not enforced
   against a Java platform type, which is exactly why `fromStage` needed a
   runtime check beside its signature. Recommend both entries, in that order.
2. **Null exit as `Died`, or a named failure `runWith(sink, ifNull)`?**
   Recommend `Died`. `fromStage` has two forms only because a *lookup* can
   legitimately miss; a sink's materialised value cannot, so a null there is
   a bug and spec 0010's rule applies.
3. **Does `Source<out A, NotUsed>` actually compile across the operator set?**
   Unknown — `concat(next.source)` wants the element in parameter position and
   may refuse the projection. Recommend letting that entry end in a recorded
   "no, because", rather than leaving a branch open.
4. **Does `M : Any` refuse any sink anyone uses?** `Sink.seq`, `foreach`,
   `ignore`, `fold` and `lastOption` are all `Any`. Recommend treating it as
   free and letting the entry prove it.
5. **Four entries or two?** The internal three buy soundness, not behaviour,
   and could wait. Recommend keeping them here: they are cheapest to do while
   the reasoning is written down, and `through` is the one a new operator
   would trip over.
