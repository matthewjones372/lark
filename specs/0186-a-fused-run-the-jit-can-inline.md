# 0186 — A fused run the JIT can inline

## Problem

A million ints through `map`, `filter`, `map` and a fold on Forks take 25.5 ms and allocate 56 MB, where Reactor's
`Flux.range` takes 18.3 ms and allocates 28 MB
([blocking-vs-reactive](https://github.com/matthewjones372/blocking-vs-reactive) `CpuChainBenchmark`, JMH, same
machine). That is Surefold's own overhead, with no thread boundary and no I/O in it, and every pipeline pays it.

The three stages are already one `Node.Fused`, so fusing is not what is missing. A profile says where the time goes:

- JFR puts 52% of the samples in `guard`, the one function every guarded body in the library is called from.
- `-XX:+PrintInlining` says why: its call to the caller's lambda "failed to inline: virtual call". One call site sees
  every map, filter and fold body in the process, so the JIT inlines none of them, and each element pays a megamorphic
  call per step.
- Allocation is the four boxes the caller's own types make: the source's `Integer`, `map`'s `Integer`, and on the half
  the filter keeps, `map`'s `Long` and the fold's `Long` (24 B each). None of the 56 B is Surefold's own object.

## Not doing

- No change to `Node`, `Step`, `fused()`, or any backend but Forks: Pekko's `FusedStage` keeps `Step.body`.
- No change to an operator's behaviour: a defect names the same operator, element and line, a raise is the same
  `Failed`, and a stop, `Releases` and metrics see what they saw.
- No bytecode generated at run time to give each pipeline call sites of its own.
- The unfused single `map`, `filter` and `mapOrFail` pulls, which still call through `guarded`. No benchmark shows
  them yet.

## Shape

No API change. In Forks' `Pull.kt`, a fused run's steps become a `Link` per kind (`Mapped`, `Kept`, `Failable`), each
calling the caller's body from its own line with the guard written in (`inline`), where `Step.body()` calls every body
through `guard`'s one line. The loop over them is the same loop.

## Why this shape

A call site that sees the bodies of one kind is what Reactor has: each of its operators is a class with its own call
to the caller's function. In a process with many pipelines a site can still see too many bodies, but it sees a kind's
share of them and not the whole library's. The alternative is a chain of links that call each other, so that a short
run compiles to straight-line code and the JIT might take the intermediate boxes away. It was measured and did not
move the allocation, and it runs into the JIT's limit on inlining a method into itself once a run has a kind twice.
Recommended: the loop.

## Stack

- [x] **`cpu-chain-overhead`** ([#528](https://github.com/matthewjones372/lark/pull/528)) — the links above, a test that a defect in each kind of fused step still names it,
      and the benchmark rows before and after.
      Done when: blocking-vs-reactive's `CpuChainBenchmark.surefold` is within 1.2× of `reactor`, or the PR says what is
      left and why.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :surefold-stream-forks:build :surefold-stream-parity:build
bench/surefold-stream-benchmarks/gate.sh origin/main "ChainBenchmark|MapParBenchmark|HandOffBenchmark|EnrichThroughputBenchmark"
```

## Open questions

- **What about a run with three bodies of one kind?** Surefold's own `ChainBenchmark.forks` has three `map`s, so its
  `Mapped` call site sees three bodies, is megamorphic again, and the row does not move (38.4 → 40.8 ns, within
  noise). Reactor has the same limit, since each of its operators has one call site. Recommended: say so, and leave
  per-position call sites, or code generated per pipeline, to a later spec if a real pipeline needs them.
- **Should the unfused single-stage pulls get the same treatment?** Recommended: yes, in a follow-up with a benchmark
  that has a lone `map`, since nothing measures it today.
- **Is the boxing worth chasing?** Reactor's 28 B is its source's `Integer` and its fold's `Long`: the JIT takes its two
  `map` boxes away and does not take Surefold's. Recommended: not here; the remaining bytes are the caller's boxes, and
  taking them away needs either per-pipeline code or primitive specialisations of the API.
