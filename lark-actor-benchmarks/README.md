# lark-actor-benchmarks

What `lark-actor` costs, measured by JMH against the same actors written by hand on Pekko Typed. Each actor
is written once per runtime doing the same work, in [`Actors.kt`](src/main/kotlin/io/github/matthewjones372/lark/actor/benchmarks/Actors.kt).
This is the baseline spec [0059](../specs/0059-an-actor-without-an-actor-system.md) asked for.

```bash
./gradlew :lark-actor-benchmarks:jmh                                    # all of it, about 3 minutes
./gradlew :lark-actor-benchmarks:jmh -PbenchmarkArgs="TellBenchmark"    # one class
./gradlew :lark-actor-benchmarks:footprint                              # heap per idle actor
```

Results land in `build/jmh-result.json`. Compare numbers only against a baseline taken on the same machine.

## Baseline, 2026-09-25

JDK 21.0.10, 4 vCPU Intel Xeon @ 2.10GHz (a shared cloud container), Pekko 1.2.1,
2 forks × 5 iterations of 1 s after 5 of warmup (3 for the blocking burst).
The raw results are in [`baseline/2026-09-25-jdk21.json`](baseline/2026-09-25-jdk21.json).

| Row | Per | lark | Pekko |
|---|---|---|---|
| `TellBenchmark.*OneToOne`: 100,000 tells from one thread into one actor | message | 478 ± 24 ns, 120 B | 177 ± 29 ns, 48 B |
| `TellBenchmark.*ManyToOne`: the same from eight virtual threads at once | message | 706 ± 37 ns, 120 B | 228 ± 18 ns, 48 B |
| `PingPongBenchmark`: a rally of 100 hops between two actors, p50 | rally | 511 µs | 481 µs |
| `PingPongBenchmark`: the same, p99 | rally | 761 µs | 732 µs |
| `BlockingBenchmark`: 100 actors × 10 steps that block 1 ms | burst | 12.5 ± 0.6 ms | 70.1 ± 0.8 ms on its blocking dispatcher, 143.8 ± 1.2 ms on the default |
| `footprint`: 100,000 actors, each run once and left idle | actor | 513 B | 1,060 B |

What the rows say:

- **Blocking is where lark wins, by 5.6×** against the dispatcher Pekko recommends for it and 11× against the
  default. A blocked step parks its virtual thread and frees the carrier, so the burst finishes 2.5 ms after
  its 10 ms floor. Pekko can only block as many steps at once as its pool has threads.
- **An idle actor costs half of Pekko's**, so a million idle entities fit in about half the heap.
- **Ping-pong is within 6–7%** at p50 and p99: a hop costs about 5 µs on either runtime, most of it the wake
  of a parked actor.
- **Plain `tell` throughput is the gap, at 2.7× to 3.1× slower and 2.5× the allocation.** Each tell takes a
  mailbox permit and counts itself in the flock's backlog, and a busy actor starts a new virtual thread every
  `throughput` (5) messages, where Pekko keeps processing on the same thread. This is the number the next
  change to the runtime has to move; a linger before parking and a larger default `throughput` are the first
  two things to try, and spec 0059 said to wait for the benchmark before trying either.
