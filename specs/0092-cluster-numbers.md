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

- [x] **`spec-0092-sharding`** — sharded tell and ask, both sides. Done when:
      the benchmarks run with `-Pjmh`, and the README's table has their rows.
      ([#259](https://github.com/matthewjones372/lark/pull/259))
- [x] **`spec-0092-persistence`** — persistent append and reliable and durable
      sends, both sides. Done when: the same.
      ([#260](https://github.com/matthewjones372/lark/pull/260))
- [x] **`spec-0092-topics`** — topic fan-out, and the README row for
      `lark-cluster`. Done when: the same, and the headline is stated.
      ([#261](https://github.com/matthewjones372/lark/pull/261))

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

Decided (2026-09-27): every open question goes as recommended. The numbers
are taken against Pekko, side by side; a result worse than Pekko's is
published as readily as a better one; and the published run is taken on the
same machine class as the existing numbers, not in this container.

Decided while building `spec-0092-sharding`:
- **Where an entity runs.** `owner = local` is an entity the telling node owns, and `remote` one another node owns. Each is found by asking entities where they run, on both sides, since lark's placement is internal.
- **The burst is 1,000, not 2,000.** A lark region's tell into its shard's full mailbox of 1,024 fails the region's step, and the region stops. A burst of 2,000 into one entity did that, and every tell after it was a dead letter. It is a limit in `lark-cluster`, left for its own change, not worked around here. Spec 0095 lifted that limit, and the burst is 2,000 again.
- **Serialisation.** Both sides write the same fields by hand over a `DataOutputStream`: a `MessageCodec` for lark, and a `SerializerWithStringManifest` for Pekko, where a reply crosses as `ActorRefResolver`'s string.
- **Nothing moves.** Pekko's rebalancing and passivation are off, and lark passivates after an hour, so an entity stays where it was found for the whole trial.
- **Gossip.** Both sides probe at their defaults. lark forms after 1 s rather than 5 s, which only shortens a trial's setup.
- **Setup and teardown.** Both sides are three nodes from one helper per runtime, and each trial starts only its own side, so the other's gossip is never running. A lark node closes without leaving (`leaveWithin = ZERO`), since all three stop together.

Decided while building `spec-0092-persistence`:
- **Through sharding.** The persistent entity is sharded, measured with its owner local and remote, so each row is a cluster's command, not a lone actor's.
- **The journals.** Each side has one H2 in memory, shared by its three nodes, each node through its own pool of 20: HikariCP under `JdbcJournal`, and Slick's under Persistence JDBC at its default size. H2 takes a login only as the user that created it, so both sides use `sa`.
- **Pekko Persistence JDBC is 1.3.0.** It ships on its own line, built against Pekko 1.1.5, so `pekko-persistence-query` is pinned to `pekkoVersion` to keep every Pekko module on 1.2.1.
- **Entities sent to keep nothing.** They confirm each command once handled, through `delivered` and `ShardingConsumerController`, so the reliable rows measure the delivery, not a journal.
- **Durable is compared, but to the entity.** Pekko's durable queue answers a send once it is stored, not once it is confirmed. The durable row ends when the entity has handled the command, the one point both sides share. lark sends an entity's next command only once the last is confirmed, and Pekko does not wait, which the README says.
- **Pekko's producer.** The benchmark's thread takes one `RequestNext` per command, from an actor that queues them, so it never sends without demand.

Decided while building `spec-0092-topics`:
- **One publish is a row, in bursts of 100.** Each invocation publishes 100 times on the first node and waits until all 30 subscribers have heard every one: under every lark mailbox of 1,024, and under Pekko's outbound queue.
- **A topic crosses once per node on both sides.** Pekko 1.2's `Topic` tells each topic instance its receptionist found, as lark's tells each `Up` member's topic, so the comparison needed no adjustment.
- **Probed before measuring.** A trial publishes a probe until every subscriber has heard one, since Pekko's receptionist finds the other nodes' topics some time after they are up.
- **The message.** lark publishes an `Int` through `Codecs.int`, and Pekko a class holding one `Int` through the benchmarks' serializer.
- **The headline is pending.** The `lark-cluster` row in the README names the comparison and says its result waits on a run on the benchmark machine. It states the result once that run is recorded.
- **One cluster table.** The benchmarks README holds every cluster row in one table, with one command for the whole of it.
