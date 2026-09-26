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

## Across nodes, 2026-09-26

After spec [0068](../specs/0068-an-actor-on-another-node.md): two nodes in one JVM on loopback, lark's over its own
TCP transport and Pekko's over Artery's TCP transport, each with the codec its messages need (a hand-written one
for lark, Pekko's built-in serializer for an `Integer`). JDK 21.0.10 on a 4 vCPU Intel Xeon @ 2.80GHz shared cloud
container, 2 forks each. The raw results are in
[`baseline/2026-09-26-jdk21-remote.json`](baseline/2026-09-26-jdk21-remote.json).

| Row | Per | lark | Pekko |
|---|---|---|---|
| `RemoteAskBenchmark`: an ask to another node and its answer | round trip | **199 ± 31 µs**, 3.5 KB | 547 ± 55 µs, 10.3 KB |
| `RemoteTellBenchmark`: 2,000 tells to another node, until all are handled | tell | **2.14 ± 0.22 µs**, 626 B | 4.03 ± 0.36 µs, 1,750 B |

- **lark's round trip is 2.8 times faster**, and allocates a third as much. Pekko's ask starts a temporary actor
  and resolves its path on the other node; lark's reply crosses as an address kept in a table until its answer
  comes back.
- **A burst of tells is 1.9 times faster.** Each lark connection is one writer draining a queue into a buffered
  socket and flushing only when the queue is empty, and the reader dispatches straight into the actor's mailbox.
- The burst stays under Pekko's outbound queue of 3,072 messages and lark's of 8,192, since both drop past theirs.

## The cell at rest, tried and dropped, 2026-09-26

Spec [0065](../specs/0065-closing-the-gaps.md)'s last entry: an emptied mailbox moved its tail back onto the cell
with one compare-and-set as each activation ended, so the next tell to an idle actor would link onto the cell it had
already loaded rather than onto the last message's node, which had gone cold. Both sides were measured one after
the other on the machine below, 2 forks each:

| Row | Per | as it is | cell at rest | Pekko |
|---|---|---|---|---|
| `FanOutBenchmark`: the whole fan-out | fan-out | 4.70 ± 0.71 ms | 4.70 ± 1.37 ms | 6.03–6.33 ms |
| `FanOutBenchmark.*Tells`: the tells alone | fan-out | 2.97 ± 0.25 ms | 3.16 ± 0.74 ms | 4.04 ms |
| `TellBenchmark.*OneToOne` | message | 133 ± 20 ns | 147 ± 11 ns | 200–211 ns |
| `PingPongBenchmark` | rally | 78.9 ± 0.9 µs | 87.8 ± 0.9 µs | 699–725 µs |

- **Nothing a tell does got faster, and ping-pong got 11% slower**: every activation that empties its mailbox pays
  the compare-and-set, and a rally empties it on every hop. The change is not in the tree.
- **The tells alone lead Pekko's** on this machine by a quarter, so there is no gap left for it to close.

## The tells alone, 2026-09-26

After spec [0065](../specs/0065-closing-the-gaps.md)'s `spec-0065-tell-row`: `FanOutBenchmark.larkTells` and
`pekkoTells` time only the loop of 10,000 tells, and wait out the actors' handling untimed before the next
invocation. Measured on a 4 vCPU Intel Xeon @ 2.80GHz shared cloud container, slower than the machine the rows
below were taken on, so only the rows in this table compare with each other.

| Row | Per | lark | Pekko |
|---|---|---|---|
| `FanOutBenchmark`: the whole fan-out | fan-out | 4.46 ± 1.01 ms, 84 B an actor | 6.16 ± 1.66 ms, 51 B an actor |
| `FanOutBenchmark.*Tells`: the tells alone | fan-out | 3.24 ± 1.87 ms | 5.49 ± 1.07 ms |

- **The tells alone are not slower than Pekko's here.** The probe that put lark's tell at 180 ns against 130 ns
  was one run of a hand-timed loop; under JMH's forks the difference goes the other way, inside wide error bars
  on a shared four-core machine.

## Runners and a leaner cell, 2026-09-26

The same machine and settings, after spec [0064](../specs/0064-a-fan-out-that-keeps-up.md): woken actors share one
runner per carrier instead of a thread each, a watcher adds runners while others are parked in blocking steps, and
an actor's hot state lives in its cell. The raw results are in
[`baseline/2026-09-26-jdk21-runners.json`](baseline/2026-09-26-jdk21-runners.json).

| Row | Per | lark | Pekko |
|---|---|---|---|
| `TellBenchmark.*OneToOne`: 100,000 tells from one thread into one actor | message | 146 ± 25 ns, 28 B | 178 ± 19 ns, 48 B |
| `TellBenchmark.*ManyToOne`: the same from eight virtual threads at once | message | 214 ± 13 ns, 26 B | 225 ± 9 ns, 48 B |
| `PingPongBenchmark`: a rally of 100 hops between two actors, p50 | rally | 69 µs | 469 µs |
| `PingPongBenchmark`: the same, p99 | rally | 139 µs | 638 µs |
| `FanOutBenchmark`: one message to each of 10,000 idle actors | fan-out | 2.72 ± 0.35 ms, 83 B an actor | 3.52 ± 0.21 ms, 50 B an actor |
| `BlockingBenchmark`: 100 actors × 10 steps that block 1 ms | burst | 12.2 ± 0.2 ms | 69.4 ± 0.2 ms on its blocking dispatcher, 142.6 ± 0.9 ms on the default |
| `footprint`: 100,000 actors, each run once and left idle | actor | 598 B | 1,060 B |

What the rows say:

- **lark leads on every row.** Fan-out, the one row Pekko led, is now 23% faster than Pekko's, from 20% slower.
- **A thread per wake could only ever tie Pekko.** 10,000 bare virtual threads, each counting down a latch, take
  3.44 ms, the same as Pekko's fan-out; 10,000 bare `ForkJoinPool` tasks take 1.11 ms. Runners make a wake a queue
  offer: a runner that runs out of work spins 10 µs and leaves, so nothing ever has to wake one.
- **The flock-wide count of activations cost a tell 60 ns** in a fan-out, since every wake and every runner wrote
  it. On virtual threads the runners now say when the flock is idle instead.
- **Blocking is unchanged** because a runner parked in a step does not count as able to run: while fewer than one
  per carrier can, the watcher starts more, doubling each 50 µs tick the shortfall lasts. Growing on progress
  instead took blocking to 30 ms, since each new runner took one actor and looked like progress.
- **Ping-pong improved** (91 to 58–72 µs across runs) because a reply's wake lands on a runner that is already
  spinning, rather than a new thread.
- **An idle actor is a third smaller** (787 to 598 B): the mailbox, the room left in it and the flags are fields of
  the cell, not objects of their own.
- **The tell to a cold actor is still slower than Pekko's**, about 180 ns against 130 ns in a probe that times the
  tells apart from the actors; the fan-out wins because the actors finish sooner. That is the next thing to take.

## With the linger, 2026-09-25

The same machine and settings, after spec 0059's linger entry: an activation that told another actor spins for
20 µs before parking. `FanOutBenchmark`, one message to each of 10,000 idle actors, is new. The raw results are in
[`baseline/2026-09-25-jdk21-linger.json`](baseline/2026-09-25-jdk21-linger.json).

| Row | Per | lark | Pekko |
|---|---|---|---|
| `TellBenchmark.*OneToOne`: 100,000 tells from one thread into one actor | message | 134 ± 5 ns, 24 B | 178 ± 15 ns, 48 B |
| `TellBenchmark.*ManyToOne`: the same from eight virtual threads at once | message | 194 ± 18 ns, 25 B | 214 ± 15 ns, 48 B |
| `PingPongBenchmark`: a rally of 100 hops between two actors, p50 | rally | 90 µs | 478 µs |
| `PingPongBenchmark`: the same, p99 | rally | 168 µs | 673 µs |
| `FanOutBenchmark`: one message to each of 10,000 idle actors | fan-out | 4.22 ± 0.28 ms, 482 B an actor | 3.52 ± 0.16 ms, 50 B an actor |
| `BlockingBenchmark`: 100 actors × 10 steps that block 1 ms | burst | 12.1 ± 0.2 ms | 69.7 ± 0.4 ms on its blocking dispatcher, 143.4 ± 0.6 ms on the default |
| `footprint`: 100,000 actors, each run once and left idle | actor | 524 B | 1,056 B |

What the rows say:

- **Ping-pong is 5× faster than Pekko's at p50 and 4× at p99.** A reply lands while its sender is still
  spinning, so no hop parks a thread and no hop wakes a carrier.
- **Only an activation that told another actor lingers.** Fan-out measured 4.25 ± 0.14 ms with no linger and
  4.21 ± 0.10 ms with it; with every activation lingering it measured 55.5 ± 0.7 ms, 20 µs spun on one of four
  carriers for each of 10,000 actors.
- **Fan-out is the one row where Pekko leads, by about 20%.** Waking an idle actor starts a virtual thread,
  about 400 ns and 480 B, where Pekko queues a task on a pool it already has. Keeping parked threads to hand
  wakes to was tried twice, as a set of idle runners and as a pool draining one queue, and both were slower (18
  and 28 ms): every hand-off paid a queue and a cross-thread unpark, and a burst of 10,000 still started
  thousands of threads. Turning off the JDK's registry of live threads (`-Djdk.trackAllThreads=false`) changed
  nothing measurable. Closing the gap for real is a scheduler of lark's own, which would be a spec of its own.

## After tuning, 2026-09-25

The same machine and settings, after spec 0059's throughput entry: a busy actor yields its carrier rather than
starting a thread, the flock's backlog counts activations rather than messages, the mailbox is a single-reader
queue, and `throughput` defaults to 64. The raw results are in
[`baseline/2026-09-25-jdk21-tuned.json`](baseline/2026-09-25-jdk21-tuned.json).

| Row | Per | lark | Pekko |
|---|---|---|---|
| `TellBenchmark.*OneToOne`: 100,000 tells from one thread into one actor | message | 137 ± 8 ns, 24 B | 179 ± 22 ns, 48 B |
| `TellBenchmark.*ManyToOne`: the same from eight virtual threads at once | message | 198 ± 20 ns, 25 B | 220 ± 16 ns, 48 B |
| `PingPongBenchmark`: a rally of 100 hops between two actors, p50 | rally | 508 µs | 475 µs |
| `PingPongBenchmark`: the same, p99 | rally | 837 µs | 755 µs |
| `BlockingBenchmark`: 100 actors × 10 steps that block 1 ms | burst | 12.1 ± 0.1 ms | 70.1 ± 1.3 ms on its blocking dispatcher, 143.3 ± 0.5 ms on the default |
| `footprint`: 100,000 actors, each run once and left idle | actor | 524 B | 1,056 B |

What changed, measured one step at a time on quick runs:

- **A busy actor keeps its thread.** Resubmitting to the executor every 5 messages started a virtual thread
  each time; yielding the carrier and carrying on costs no thread. Allocation fell from 120 B to 29 B a message.
- **64 messages between yields, not 5.** The profile put a quarter of runnable time in `ForkJoinPool.signalWork`
  waking a parked carrier on each yield. `tell` 1→1 fell from about 400 ns to about 115 ns.
- **A single-reader mailbox.** A send is one swap that never retries, where `ConcurrentLinkedQueue` retried its
  tail CAS whenever senders met. `tell` 8→1 fell from about 308 ns to about 185 ns.
- **The backlog counts activations.** `awaitIdle` no longer puts a shared atomic on every message.

`tell` is now faster than Pekko's on both rows with half the allocation. Ping-pong is 7% behind at p50 and
11% at p99, a little further than before; a hop is still one wake of a parked actor on either runtime, and
that wake is what a linger before parking would take off.

## Baseline, 2026-09-25 (before tuning)

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
