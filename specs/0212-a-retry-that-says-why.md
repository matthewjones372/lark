# 0212 — A retry that says why

## Problem

A `transact` (spec 0209) that keeps retrying is slow, and nothing says why. Every STM today leaves contention to
guesswork. A team sees latency climb, can't tell which value the steps are fighting over, and ends up replacing
STM with locks. The engine already knows the answer: when a step's read check fails, it knows exactly which value
changed under it.

## Not doing

- **No automatic fixes.** No throttling or backing off in this spec, just reporting.
- **No per-value tags for unnamed values.** A `Shared` per order would make a metric tag per order. Only named
  values become tags.
- **No tracing of every run.** A span per run would cost more than the step.

## Shape

```kotlin
val stalls = Shared(120, name = "free.stalls")
val circle = Shared(60, name = "free.circle")

fun hold(n: Int) = transact("hold") { … }
```

Through [metrics](observability.md#metrics), read from the `metrics` local as the policies do:

| Metric | Kind | Tags |
|---|---|---|
| `surefold.transact.runs` | counter | `transact`, `outcome` (`committed`, `failed`, `cancelled`) |
| `surefold.transact.retries` | counter | `transact`, `cause` (a `Shared`'s name, `unnamed`, or `waited`) |
| `surefold.transact.attempts` | histogram | `transact`; runs per commit |

From these, a dashboard can show *"`hold` retried on 38% of runs; 91% of those on `free.stalls`"*.

- With `surefold-otel`, a step that retries more than once adds one event to the current span when it commits:
  `transact.retried`, with the attempt count and each cause's count.
- A `transact` with no name is tagged `unnamed`. A `Shared` with no name is reported as `unnamed`.
- Recording costs nothing on a step that commits on its first run beyond one counter increment.

## Why this shape

Names are given by hand. **Alternative:** take the `transact` name from the call site, through 0210's compiler
plugin or a stack walk. The plugin isn't always applied, and a stack walk on every retry costs more than the
retry. Recommended: optional names, with `unnamed` as the default, so reporting works without any setup.

## Stack

- [ ] **`spec-0212-causes`** — the engine records which `Shared` failed each read check. `transact(name)` and
      `Shared(…, name)` take names.
      Done when: a test that forces a conflict on a named value sees that name as the cause, and a conflict on an
      unnamed one sees `unnamed`.
- [ ] **`spec-0212-metrics`** — the three metrics, recorded through `metrics`, and the table in
      `docs/core.md` beside the policies'.
      Done when: a `capturingMetrics` run of two contending steps reads `runs`, `retries` and `attempts` with the
      right tags.
- [ ] **`spec-0212-otel`** — the span event in `surefold-otel`.
      Done when: a step that retries 3 times adds one event with `attempts=4` to the active span.

## Acceptance

```bash
./gradlew build
```

## Open questions

Taken as recommended (maintainer, 2026-10-26). Each answer is the question's recommendation.

1. **Should `cause` name only the first value that changed, or each one?** Recommended: the first. That is what
   ended the run, and it keeps one increment per retry.
2. **Warn in the log when a step's retries pass a threshold?** Recommended: no. A log line per hot step is noise.
   Alert on the metric instead.
3. **Name a `Shared` from its property name** (`val stalls = Shared(120)` names itself `stalls`) through a
   delegate? Recommended: not now. An explicit name is one argument.
