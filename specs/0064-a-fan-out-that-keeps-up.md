# 0064 — A fan-out that keeps up

## Problem

Waking an idle `lark-actor` actor starts a virtual thread, and a fan-out wakes
thousands at once: one message to each of 10,000 idle actors takes 4.2 ms
where Pekko takes 3.5 ms, the one row of the benchmark where lark is behind.
Measured on the same machine:

| 10,000 wakes | time | allocated each |
|---|---|---|
| a bare virtual thread each | 3.44 ms | 320 B |
| Pekko's fan-out | 3.46 ms | 50 B |
| a bare `ForkJoinPool` task each | 1.11 ms | 40 B |

So a thread per wake can at best tie Pekko. Timing the sender apart from the
actors shows the other half: the tells alone take 2.5 ms against Pekko's
1.5 ms, 254 ns a tell to an idle actor against 145 ns, because a tell touches
some eight objects of the actor's, each a likely cache miss, where Pekko's
touches three or four.

Transport, membership and sharding, which 0059 numbered 0064–0066, move to
0065–0067.

## Not doing

- **A work-stealing scheduler of lark's own.** The carriers stay the JDK's;
  what changes is how many virtual threads lark asks them to run.
- **Changing what a step may do.** A step still blocks freely, and a flock of
  blocking actors must stay as fast as it is now (12 ms for 100 actors × 10
  steps of 1 ms, against Pekko's 70 ms on its blocking dispatcher).
- **Other executors.** A flock on an executor that is not `VirtualThreads`
  keeps an activation per task, as now.

## Shape

Nothing in the API changes. Inside a flock on virtual threads:

- **Runners.** A woken actor joins the flock's ready queue, and a small set of
  runner threads, one per carrier, take actors from it in turn. A runner that
  finds the queue empty spins briefly before it leaves, so a steady stream of
  wakes starts no threads at all, and a burst starts a handful rather than one
  each. A busy actor goes to the back of the queue after `throughput`
  messages, as it yields its carrier now.
- **Blocking steps.** While work is queued, a watcher looks at the runners
  every 50 µs, and counts as unable to run any that are in a step and parked
  (`WAITING`, `TIMED_WAITING` or `BLOCKED`). If fewer than one runner per
  carrier can run, it starts runners to make up the shortfall, doubling each
  tick the shortfall lasts, up to 2,048. A burst of blocking actors is each on
  a runner of its own within a few ticks, and a fan-out, whose runners are all
  running, never grows. A blocking step still parks only its own runner.
  Watching for progress instead was tried first: each new runner took one
  actor and looked like progress, so the runners grew by one a tick and
  blocking took 30 ms rather than 12.
- **Idle.** On virtual threads the flock is idle when nothing is queued and no
  runner has an activation in hand, which each runner marks on itself; the
  flock-wide count of activations, which every wake and every runner wrote,
  is kept only for other executors. It cost a tell 60 ns in a fan-out.
- **Stop and interrupt.** A runner serves many actors, so a stop interrupts it
  only while it is running that actor's step, under the actor's own monitor,
  and a runner clears any interrupt left over before it moves on.
- **A leaner cell.** The flags a tell reads and writes, the mailbox's head
  and tail, and the count of room left are fields of the actor's cell,
  updated through `VarHandle`s, rather than objects of their own. A tell to an
  idle actor touches the cell, its new node and the ready queue.

## Why this shape

Pekko wins a fan-out because waking an actor queues a task that already
exists on threads that already run. Runners give lark the same: a wake is a
queue offer, and only a burst the runners cannot keep up with starts a
thread. Earlier tries parked idle runners and paid a cross-thread unpark for
every wake; a runner that spins and then leaves pays nothing to be woken,
because nothing ever wakes it. The watcher is what keeps the reason lark exists
intact: a step may block, and when every runner is blocked the flock grows
rather than stalls. A per-actor object costs a cache miss every time a tell
reaches an actor that has gone cold, and a fan-out reaches only cold actors,
so the cell's hot state lives in the cell.

## Stack

- [ ] **`spec-0064-runners`** — the ready queue, runners, the watcher, and
      interrupts that stay with their actor. Done when: `FanOutBenchmark`'s
      lark row is no slower than Pekko's, and blocking, ping-pong and tell
      are within their error of the last baseline.
- [ ] **`spec-0064-cell`** — the cell's flags, mailbox and room as fields.
      Done when: a tell to an idle actor is no slower than Pekko's in the
      probe, fan-out beats Pekko's, and an idle actor is smaller.
- [ ] **`spec-0064-baseline`** — the full benchmark, the README's table and
      its JSON. Done when: the README shows where lark stands on every row.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-actor-benchmarks:jmh
```

## Open questions

Nothing: the user asked for fan-out at least at parity with Pekko, and for the
decisions to be made along the way. They are made here, each with the
measurement that made it, and any the build changes are written back.
