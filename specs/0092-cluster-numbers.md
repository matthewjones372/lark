# 0092 — Cluster numbers

## Problem

`lark-actor-benchmarks` measures one node and remoting against Pekko: tell,
ping-pong, blocking steps, fan-out, idle footprint, and a remote ask and burst
(0059, 0068). Nothing measures what 0069–0085 added, which is what a clustered
service actually pays for:
- a sharded tell that may cross a node;
- a persistent entity's append;
- a reliable send and its confirmation;
- a durable producer's two appends per command;
- a topic's fan-out.

The README claims speed for the actor and remoting layers and says nothing
about the cluster. A service choosing between lark and Pekko Cluster Sharding
has no numbers, and a change that makes sharding twice as slow would pass
every test.

## Not doing

- **Multi-machine runs.** The benchmarks run nodes in one JVM over loopback,
  as `RemoteBenchmark` does; network cost is out of scope, and so is anything
  loopback cannot show.
- **Databases other than H2 in memory.** Journal throughput on Postgres is the
  database's number, not lark's.
- **A regression gate in CI.** Numbers are recorded in the README with how
  they were taken, as the existing benchmarks are; gating on them needs quiet
  hardware CI does not have.

## Shape

New JMH benchmarks in `lark-actor-benchmarks`, each against Pekko where Pekko
has the same thing:

| Benchmark | lark | Pekko |
| --- | --- | --- |
| Sharded tell, owner local and remote | `Sharded.entity(id).tell` | Cluster Sharding `EntityRef.tell` |
| Sharded ask round trip | `ask` on an entity | `EntityRef.ask` |
| Persistent append, one event per command | `persistent` on H2 | Persistence Typed on JDBC H2 |
| Reliable send to confirmation | `reliable` | `ShardingProducerController` |
| Durable reliable send | `reliable(durable = true)` | durable producer queue |
| Topic publish to 3 nodes × 10 subscribers | `cluster.topic` | `Topic` (distributed pub-sub) |

- **Same shape both sides.** Three nodes in one JVM, the same message
  classes, and the same serialisation cost per message.
- **Numbers in the README.** `lark-actor-benchmarks/README.md` gains a
  cluster table, with the machine, JDK and JMH settings. The README's
  `lark-cluster` row states the headline result, whichever way it falls.

## Why this shape

Measuring against Pekko in the same JVM on the same messages gives a
comparison a reader can reproduce with one command, which is how the existing
actor numbers were earned. The alternative is absolute throughput with no
baseline, which is cheaper but says nothing a reader can act on. Recommended:
side by side, and publish the result whichever way it goes.

## Stack

- [ ] **`spec-0092-sharding`** — sharded tell and ask, both sides. Done when:
      the benchmarks run with `-Pjmh`, and the README's table has their rows.
- [ ] **`spec-0092-persistence`** — persistent append and reliable and durable
      sends, both sides. Done when: the same.
- [ ] **`spec-0092-topics`** — topic fan-out, and the README row for
      `lark-cluster`. Done when: the same, and the headline is stated.

## Acceptance

```bash
./gradlew :lark-actor-benchmarks:jmh
```

## Open questions

- **Against Pekko, or absolute numbers only?** Recommended: against Pekko, as
  above.
- **Publish a result that is worse than Pekko's?** Recommended: yes. A number
  hidden when it is bad makes every other number worth less.
- **Where are they run?** Recommended: on the same machine class as the
  existing numbers, recorded beside them. This container's numbers are
  noisy, so the published run is yours.
