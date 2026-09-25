# lark-stream-benchmarks

What today's `lark-stream` costs, measured by JMH. This is the baseline that spec
[0046](../specs/0046-a-stream-described-once.md) must match and
[0047](../specs/0047-a-stream-the-compiler-sees-whole.md) must beat.

```bash
./gradlew :lark-stream-benchmarks:jmh                                    # all of it, about 3 minutes
./gradlew :lark-stream-benchmarks:jmh -PbenchmarkArgs="ChainBenchmark"   # one class
```

Results land in `build/jmh-result.json`. A new baseline is committed to `baseline/`,
named by date and JDK. Compare numbers only against a baseline taken on the same machine.

## Baseline, 2026-09-25

JDK 21.0.10, 4 vCPU Intel Xeon @ 2.10GHz (a shared cloud container), Pekko 1.2.1,
2 forks × 5 iterations of 1 s after 5 of warmup.
The raw results are in [`baseline/2026-09-25-jdk21.json`](baseline/2026-09-25-jdk21.json).

| Row | Per | Time | Allocated |
|---|---|---|---|
| `ChainBenchmark.lark`: `map`, `map`, `filter`, `map`, `mapOrFail`, `runFold` | element | 149.5 ± 10.9 ns | 84 B |
| `ChainBenchmark.pekko`: the same five stages on Pekko, unguarded | element | 130.2 ± 6.6 ns | 72 B |
| `GroupedWithinBenchmark.lark`: `groupedWithin(100, 1.seconds)` | element | 114.2 ± 6.9 ns | 19 B |
| `MapParBenchmark.lark`: `mapPar(8)` with a body that multiplies | element | 8,695 ± 224 ns | 1,082 B |
| `RunManyBenchmark.describedOnce`: 10 elements, `Run` built once | run | 70.5 ± 3.8 µs | 8.5 KB |
| `RunManyBenchmark.describedEachTime`: the same, `Run` built per run | run | 82.5 ± 5.3 µs | 18.0 KB |

What the rows say:

- lark's guards add about 15% time and 12 B per element on top of hand-written
  Pekko. This is what 0047's merge removes before it removes anything of
  Pekko's.
- `mapPar` forks a virtual thread per element, which costs about 8.7 µs and
  1 KB. It is the right operator for a blocking body and the wrong one for a
  cheap one. Any fork backend (0046) has to beat this number, not the chain's.
- Starting a run costs about 70 µs whatever it carries. Building its stages adds
  another 12 µs and 9.5 KB, which is what 0046's compile-once requirement keeps
  off a pipeline described once.

## Forks beside Pekko, 2026-09-25

The same descriptions on the two backends, after spec 0046's split: the chain and the
per-run rows, 2 forks each. Raw results are in
[`baseline/2026-09-25-jdk21-forks.json`](baseline/2026-09-25-jdk21-forks.json).

| Row | Per | Pekko | Forks |
|---|---|---|---|
| `ChainBenchmark`: the five-stage chain | element | 151.3 ± 9.5 ns, 84 B | 28.9 ± 4.0 ns, 84 B |
| `RunManyBenchmark`: 10 elements, described once | run | 68.6 ± 4.8 µs, 6.5 KB | 30.9 ± 1.3 µs, 0.7 KB |

- On Forks the chain is one pull loop on one thread, with no hand-off between stages,
  which is where Pekko's time per element goes. The 84 B per element that both
  backends allocate is the boxed `Long` each stage answers with, and is the next thing
  a compiled stream (0047) can remove.
- A run on Forks starts one virtual thread and nothing else, where Pekko materialises a
  graph.

## Fused, 2026-09-25

After spec 0047's `spec-0047-fuse`: adjacent `map`, `mapOrFail`, `filter` and `filterNot`
compile to one stage. Raw results are in
[`baseline/2026-09-25-jdk21-fused.json`](baseline/2026-09-25-jdk21-fused.json).

| Row | Per | 0046 baseline | Fused |
|---|---|---|---|
| `ChainBenchmark.lark` | element | 149.5 ± 10.9 ns | 91.1 ± 8.3 ns (−39%) |
| `ChainBenchmark.pekko`, hand-written, unguarded | element | 130.2 ± 6.6 ns | 135.7 ± 6.2 ns |
| `ChainBenchmark.forks` | element | 28.9 ± 4.0 ns | 29.9 ± 2.8 ns |

The described chain on Pekko is now a third faster than the same five stages written
against Pekko by hand, because it is one stage where they are five. On Forks the stages
were already calls in one loop, so fusing changes nothing measurable there.

## The gate

```bash
lark-stream-benchmarks/gate.sh origin/main                  # every row, the base against this checkout
lark-stream-benchmarks/gate.sh HEAD "ChainBenchmark"         # uncommitted changes against the last commit
```

`gate.sh` runs the benchmarks on the ref in a worktree and then on this checkout, on the same machine
one after the other, and `jmhCompare` fails when a row is both more than 10% slower (`-Ptolerance` to
change it) and outside both error bars. The `benchmarks` workflow runs it on every pull request that
touches a stream module and keeps each run's table as an artifact.

The committed numbers above are records, not thresholds: they were measured on one machine, and a gate
against them on another would be measuring the machine.

## Where the time goes

```bash
./gradlew :lark-stream-benchmarks:profiles     # build/profiles/<Benchmark>.mmd and .txt
```

Each benchmark's pipeline is run once, measured on Pekko with every element sampled, and drawn
with its profile: each stage's share of the busy time, its busy and waiting per element, and
what it emitted. In the Mermaid, a stage is `hot` from half the time and `warm` from a fifth.
The profiles taken with the fused baseline are in
[`baseline/profiles-2026-09-25`](baseline/profiles-2026-09-25). The `benchmarks` workflow uploads
a fresh set with every gate run.
