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
