# 0148: a profile knows its stages

## Problem

A `Profile` holds numbers keyed by a bare `Int`: a stage's place in
`dataOrder()` (spec 0050). That index crosses three modules:

1. `Instruments` turns it into a string tag, `"step" to step.toString()`.
2. `Profiler.tally` parses it back with `toIntOrNull()`.
3. `Run.render(profile = …)` works out `dataOrder()` again on whichever run it
   is given and matches numbers by position (`Render.kt:42`).

Nothing checks that the profile and the run agree, and it goes wrong silently
in three ways:

- **Wrong run:** a profile rendered against a different run, or against the
  same run after an edit, puts every number beside the wrong stage.
- **Shared profiler:** two measured pipelines that share one `Profiler` are
  summed into one profile. The tally key is `(name, step)`, and the
  `pipeline` tag is ignored.
- **Optimised view:** rendering with `optimised = true` is guarded only by a
  `require` (`Render.kt:30`). Any future change to `dataOrder` would bypass it.

## Not doing

- Changing what a profile measures, the metric names, or their tags.
- Changing how a profile is used, as the README shows: `Measured(name,
  profiler)`, run it, then `render(profile = profiler.profile())`.
- Rendering a profile against the optimised tree, which is a separate spec if
  anyone wants it.

## Shape

```kotlin
data class StageProfile(
    val elements: Long,
    val busyMillis: Double? = null,
    val waitingMillis: Double? = null,
    val busySampledMillis: Double = 0.0,
    val stage: String? = null,  // new: the operator, from the `stage` tag
    val at: String? = null,     // new: where it was written, from the `at` tag
)

class Profiler(also: Metrics = NoMetrics) : Metrics {
    fun profile(pipeline: String? = null): Profile  // new optional argument
}
```

- `Profiler` tallies by `(pipeline, step)` and keeps each step's `stage` and
  `at` from the tags it already receives.
- `profile()` with no argument works as today while only one pipeline has
  reported. With more than one, it refuses and names them: "this profiler saw
  ingest and export; say which".
- `render` checks each stage it prints against the profile's `stage` and `at`
  for that step. On the first mismatch it fails with both sides, for example
  "step 3 is map at Pipelines.kt:16 in the profile, filter at Pipelines.kt:17
  in this run".
- A hand-built `StageProfile` without `stage` is not checked, so `RenderTest`
  keeps working.

## Why this shape

We agreed in chat that `measured(...)` would hand the profiler its stages. The
code shows that would need `measuredBy` to check `metrics is Profiler`, a type
test across a seam that today is plain `Metrics`. The stage name and site
already reach the profiler as tags, so it can collect each stage's identity
from them with no new seam. The profile then carries its stages, which is what
the decision was for. The step stays a key, but it is no longer trusted on its
own.

## Stack

- [x] **`spec-0148-identity`** ([#666](https://github.com/matthewjones372/lark/pull/666)): `StageProfile.stage`/`at`; `Profiler` keeps
      them and keys by pipeline; `profile(pipeline)`.
      Done when: a `ProfileTest` case with two pipelines on one profiler gets
      separate profiles, and `profile()` refuses with both names.
- [x] **`spec-0148-render`** ([#667](https://github.com/matthewjones372/lark/pull/667)): `render` checks identity and fails on mismatch.
      Done when: a test that renders a profile against an edited run fails
      with the stage named, and `RenderTest`'s goldens are unchanged.

## Acceptance

```bash
./gradlew :surefold-stream:test :surefold-stream-parity:test
./gradlew :surefold-stream-benchmarks:compileKotlin
```

## Open questions

Answered (maintainer, 2026-10-25: the recommendations stand).

1. ~~Should the `require` against `optimised` with a profile stay?~~ Yes: its message says what to do, while a
   mismatch message only says what went wrong.
2. ~~Should `profile()` refuse when it sees several pipelines, or return the one that reported last?~~ Refuse,
   naming them.
3. ~~Should `ProfileTest` move from surefold-stream-parity to surefold-stream?~~ No: it needs a backend to run a stream.
