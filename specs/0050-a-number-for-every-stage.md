# 0050 — A number for every stage

## Problem

A slow pipeline has one slow stage, and nothing says which. lark's `Metrics`
(0034) is how a service counts things, and `lark-micrometer` backs it. A
pipeline reaches it only through a `wireTap` or a counter written into a `map`
body by hand, and neither can see time spent between stages.

Once 0046 lands, a backend compiles every stage, so a backend can wrap any
stage it chooses. Once 0049 lands, every stage has a stable name to tag a
number with.

## Not doing

- **No tracing.** One span per element costs more than most stage bodies.
  `lark-otel`'s spans stay at the handler and run level.
- **No always-on instrumentation.** A run is measured only when asked. An
  unmeasured run compiles exactly as it does today.
- **No per-element tags.** The tags are the stage and the pipeline, and
  nothing whose value set is unbounded.

## Shape

```kotlin
relay.start(PekkoStreams(system), measured = Measured(metrics, pipeline = "relay"))
```

For each stage, tagged `pipeline` and `stage` (the name and site from 0049):

| Metric | Kind | What |
|---|---|---|
| `lark.stream.elements` | counter | elements a stage has emitted |
| `lark.stream.busy` | histogram | time inside the stage body, per element |
| `lark.stream.waiting` | histogram | time a stage waited for downstream demand |

- A `Fused` stage (0047) is measured once for the whole segment, and each
  merged step is counted. A timer around every step would cost more than the
  merge saved.
- `waiting` is the number that names a bottleneck: the stage upstream of a slow
  one waits, and the slow one does not.

## Why this shape

Wrapping at compile time needs no change to the pipeline. The alternative is a
`measured()` operator the user places, which is what `wireTap` already
approximates. Recommended against, because a user places it after the stage
they suspect, not the one that is slow.

## Stack

- [ ] **`spec-0050-counts`**: `Measured`, and `elements` and `busy` on both backends.
      Done when: a pipeline with one deliberately slow stage shows that stage's `busy`
      above every other.
- [ ] **`spec-0050-waiting`**: `waiting`.
      Done when: the stage upstream of the slow one has the highest `waiting`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Is `busy` a histogram per element or a sampled timer?** Recommended:
   sample one element in 64 by default. A clock read per element costs about
   what 0047's merge saves.
2. **Does measuring disable 0047's merge?** Recommended: no. Measure the merged
   segment, and let `render(optimised = true)` say what a segment contains.
