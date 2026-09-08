# 0010 — A defect is never silent

## Problem

A `null` completion or a throw inside a stage ends the stream and reaches
`run` as `Exit.Died(cause)`. That is a value, and a sealed `when` has to
handle it. The one way to lose it is the common one: a pipeline run for its
effect, `.to(Sink.ignore()).run(system)` or a `run` whose stage nobody
reads. Pekko fails the materialised value and says nothing else, so a price
feed stops and the log stays quiet — the shopping cart's `PricingService`
has this shape today.

The cause is not clear either. The `NullPointerException` names the
operator and nothing more: not the element the stage was working on, and
not the line in the caller's code that built the operator, since the trace
starts inside the library's own `thenApply`. `fromStage` (spec 0009) names
its builder; nothing else does.

Dipper built this as its spec 0002 after spec 0005 imported it here. That
repository is deleted; the patch is held outside it and this spec is what
brings the behaviour home.

## Not doing

- **No `ifNull` on `mapAsync`.** A null is a bug, not a domain answer.
  `mapOrFail` names a missing value where the code can see it is nullable;
  `fromStage(stage, ifNull)` stays for the one builder that starts a stream
  from a lookup.
- **No change to `Exit`.** `Died` stays a value; a `run` that completed its
  stage exceptionally would be lost by exactly the caller this spec is for.
- **No supervision, no restart.** What to do after a defect is the caller's.

## Shape

```kotlin
// A stream run for its effect, its stage discarded. The defect still lands.
Stream.from(rows).mapAsync(4) { ledger.settle(it) }.runFold(0) { n, _ -> n + 1 }.run(system)

// [ERROR] lark-stream: mapAsync died on Row(id=3, customer=null), built at IngestService.kt:41:
//         the stage completed with null
```

- `run` reports every `Exit.Died` at error through the actor system's own
  logger (`classicSystem().log()`) before handing the `Exit` back, cause
  attached. A caller who handles `Died` sees it twice; one who discards the
  stage still sees it once.
- A defect's message names the operator, the element it was processing,
  and where the operator was built. The build site is the first frame
  outside the library's own code source, captured once per operator when
  the pipeline is described, so the running path pays nothing; the element's
  `toString` is read only when a defect happens.
- `Exit.Died(cause)` keeps the caller's own throwable. Where the library
  raises it (a null completion) the facts are its message; where the
  caller's body threw, or a stage completed exceptionally, the facts ride as
  a suppressed exception on the cause, so `is IllegalStateException` still
  matches.
- Every operator that runs caller code takes part, on `Pipe` as well as
  `Stream`: `map`, `mapOrFail`, `filter`, `mapAsync`, `mapPar`, `absolve`,
  `divertLefts`. One private guard, not seven copies.

## Why this shape

A library whose claim is that nothing is dropped silently cannot leave the
last step to the caller's discipline. Logging from a library is unusual, and
the alternative is the one the consumer has: a defect that reaches nobody.
The element is in the message because a person reading the log wants to
know which one; the build site because a stage failure's trace otherwise
names only the library.

## Stack

- [ ] **`spec-0010-reported`** — the error log in `run`, the guard on every
      operator, the element and build site in a defect's message.
      Done when: a test with nobody reading the stage sees the error through
      `LoggingTestKit`, and the message carries the element and a line of
      the test's own file.

## Acceptance

```bash
./gradlew build
```

## Open questions

1. **The element in the log by `toString`?** Recommend yes: it is the
   caller's own type, and a log without the element is the one nobody can
   act on.
2. **`Exit<Nothing, A>.orThrow()`, as dipper had?** `awaitExit` already
   rethrows a `Died` inside a `Raise`. Recommend not: one spelling.
3. **Does `mapPar`'s existing catch join the guard, or keep its own?**
   Recommend join: one place decides what a defect's message says.
