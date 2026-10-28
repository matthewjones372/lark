# 0224 — A journal the nodes keep among themselves

## Problem

Every event a persistent actor writes waits for a Postgres commit. With group commit (spec 0108) an append takes
0.4 to 0.58 ms, and the bank writes about eight a transfer, one after another: 3 to 5 ms of every transfer is the
journal, before the HTTP, the hops and the queueing. Postgres is also a second system to run, and on the bank's
4-core host its backends share the CPU the nodes need. Trading systems keep the write path off disk: a command
counts once a majority of nodes hold it, and disk is written beside it. Surefold has no journal that works so.

## Not doing

- **A dependency.** No Raft library and no Aeron: the module adds nothing beyond Surefold modules.
- **Changing what an append promises.** The same `Either<JournalConflict, Long>`, the same order within an id.
- **Changing the voters while running.** The voters are fixed by configuration; changing them is stop, edit, start.
- **Compaction, Raft snapshots or `JournalPruning`.** The log keeps every event, as an event journal does anyway.
- **Snapshots and read-model offsets.** They stay in `JdbcSnapshots` and `JdbcOffsets`, or in memory.
- **Several Raft groups, or a leader placed beside each shard's owner.** One group, until the hop is measured.
- **Moving a JDBC journal's events into this one,** and UDP or kernel bypass.

## Shape

A new module, `surefold-actor-journal-raft`, on `surefold-actor` and `surefold-actor-remote`.

```kotlin
val journal = RaftJournal(
    self = Node("bank-1", "bank-1", 7355),
    voters = listOf(Node("bank-1", "bank-1", 7355), Node("bank-2", "bank-2", 7355), Node("bank-3", "bank-3", 7355)),
    directory = Path.of("/var/lib/bank/journal"),
    durability = Durability.Flushed,
)
journal.start()
scope.journal(journal)                        // nothing else in the service changes
journal.append(id, expected = 3, events)      // answers once a majority holds the entry
```

- **One Raft log for the whole journal.** An append on a follower goes to the leader; the leader replicates it and
  answers once a majority holds it. Without a leader, as during an election, an append throws
  `JournalUnavailable` and its caller retries, as it does for a database that is down.
- **`Durability`:** `Flushed` answers once a majority has written and fsynced the entry, each node's fsync taking
  whatever is waiting, as 0108's committer does. `Replicated` answers once a majority holds it in memory, and
  flushes behind; every voter losing power inside that window loses answered events.
- **A conflict is decided when an entry is applied,** in log order, the same on every node: an entry whose
  `expected` is not the id's last is committed, applies as nothing, and answers its conflict.
- **The feed's offset is the log index.** `RaftJournal` is a `SlicedFeed`, and only committed entries are seen, so
  no offset appears before a smaller one.
- **On disk:** a segmented append-only log with a CRC per record; a torn record at the tail is cut on open, and
  the index of ids to positions is rebuilt by reading the log.
- **On the wire:** Raft's messages are `Frame`s on a `Transport` of the journal's own, on its own port, with TLS
  when the transport has it.

## Why this shape

Behind `Journal`, nothing above it changes: `Persistent.kt` already blocks on `append`. One Raft group puts every
append one hop from the leader, about 0.1 ms in a data centre; one group per range of slices, led where the shard
lives, would save that hop and cost every ownership move a leader transfer, so it waits for numbers. Writing Raft
here, rather than taking Apache Ratis or Aeron Cluster, keeps the dependency rule and fits the narrow `Journal`;
its price is the simulation suite in the stack, which is not optional. Aeron Cluster is the alternative if that
price is too high: proven and faster on the wire, but it brings a media driver, an archive and its own service model.

## Stack

- [ ] **`spec-0224-log`** — the segmented log: append, grouped flush, read by index, cut after an index, CRC.
      Done when: a log cut at every byte of its last record reopens with every flushed record and nothing torn.
- [ ] **`spec-0224-election`** — Raft's terms, votes and timeouts as a pure state machine, on a simulated network.
      Done when: across 10,000 seeds of partitions, drops, reorders and crashes, no term has two leaders.
- [ ] **`spec-0224-replication`** — appending entries, the commit index, and a follower's log repaired.
      Done when: across the same seeds, every entry answered as committed is in every later leader's log.
- [ ] **`spec-0224-journal`** — `RaftJournal` as `Journal` and `SlicedFeed`, forwarding, `JournalUnavailable`.
      Done when: `JournalContract` passes on three voters in one JVM, and a killed leader loses no answered append.
- [ ] **`spec-0224-wire`** — Raft's frames on `surefold-actor-remote`'s `Transport`.
      Done when: `JournalContract` passes on three voters talking over loopback sockets.
- [ ] **`spec-0224-measured`** — `SpreadPersistentBenchmark` against `JdbcJournal` with group commit, both
      durabilities, and the bank's 400-a-second profile. Done when: the numbers are in this spec.

## Acceptance

```bash
./gradlew build
./gradlew :surefold-actor-journal-raft:test -Psimulation.seeds=100000
./gradlew :surefold-actor-benchmarks:jmh -Pjmh.includes=SpreadPersistent
```

## Open questions

1. **Write Raft here, or take Aeron Cluster as the module's one library?** Here, for the dependency rule and the fit
   with `Journal`; Aeron Cluster if a reviewer will not take a Raft no one else runs.
2. **Which durability by default?** `Flushed`: for a bank, losing answered transfers to a power cut across a whole
   rack is not a trade to make by default. `Replicated` is the one to turn on and measure.
3. **Where does `read` go?** To the node asking, once it has applied up to the leader's commit index (Raft's read
   index, one round trip), rather than to the leader, so that recovering an entity does not ship its events.
4. **Six entries is past one page's worth.** Split into a Raft spec (log, election, replication) and a journal spec
   (journal, wire, measured)? Recommended, before anything is built.
