# 0099 — Commands a crashed node left behind

## Problem

A durable producer (0085) keeps its unconfirmed commands in the journal, so a
crash loses none of them. But nothing sends them until a producer with the
same id starts again. `Sharded.reliable(durable = true)` leaves that to the
service. A service that restarts the node in place gets them back. One whose
node is gone for good, such as an autoscaled pod or a lost machine, does
not, and its commands wait in the journal for good.

lark-bank (0094) needed this and built it by hand. Each node names its
producers after its own life, and when a member is `Removed`, the oldest
`Up` member it knows starts that member's producers again. Building it showed
three gaps that a service cannot close alone:

- **Two resumers.** Right after a crash, two survivors can each believe they
  are the oldest. Both start the same producer id, and they conflict in the
  journal as two writers do (0063).
- **A resumer that dies.** If the node that resumed another's producers
  crashes before they drain, nobody resumes them again. Survivors only react
  to the `Removed` of the node that owned the commands, and that event has
  already passed.
- **Ids that pile up.** Every life of every node leaves producer ids in the
  journal. Nothing marks one finished, so each start has more to think about.

## Not doing

- **A plain flock.** Without a cluster there is no one else to resume a
  producer. Restarting the process with the same id is the answer there, as
  it is today.
- **Moving a live producer.** Only the producers of a life that is gone are
  resumed. A running producer stays where it is.
- **Exactly once.** Resumed commands are resent, and the entity drops
  duplicates, as with any resend (0079).
- **In-memory producers.** Their commands die with the node, by design.

## Shape

```kotlin
// each node, as today; the id no longer needs the node's life in it
val payments = wallets.reliable("checkout", durable = true)
```

- **Named by life.** A durable producer from `reliable` is kept under
  `kind-producerId-life`, where the life is the node's name and `uid`
  (`Cluster.uid`, 0097). The service passes the same `producerId` on every
  node and every restart.
- **One registry.** A cluster singleton, `lark-producers`, keeps a
  persistent list of every durable producer and the life that runs it. A
  producer registers when it starts.
- **Resumed by the singleton.** When a life is `Removed`, the singleton
  starts each of that life's producers on its own node and becomes their
  runner in the list. Being a singleton, it is the only resumer.
- **Resumed again.** When the singleton starts, on its first node or after a
  hand-over, it resumes every producer whose runner is not a live member.
  That covers a resumer that died, because the new singleton finds the
  producers the dead one was running.
- **Retired once drained.** A resumed producer takes no new sends. Once it
  has nothing unconfirmed, the singleton stops it, prunes its events, and
  removes it from the list.

## Why this shape

Deciding who resumes a dead life's work is the same decision as where a
singleton runs, and lark already makes that decision once, with a hand-over,
for every singleton. The registry turns "resume on `Removed`" into "resume
whatever the list says has no live runner", which the singleton checks on
every start, so a second crash is covered by the same code as the first. The
alternative is for each node to decide by itself, as the bank does. That
needs no singleton, but two views can disagree and nothing covers the
resumer's own death. Recommended: the singleton.

## Stack

- [ ] **`spec-0099-life`** — durable producers from `reliable` are named by
      the node's life. Done when: two lives of one node, restarted in place,
      keep separate outboxes in the journal, and each one's commands are
      delivered.
- [ ] **`spec-0099-registry`** — the `lark-producers` singleton and each
      durable producer's registration. Done when: on three nodes, the list
      names each node's producer and its life.
- [ ] **`spec-0099-resume`** — resume on `Removed` and on the singleton's
      start, and retire once drained. Done when, on three nodes, each with
      commands unconfirmed:
      - a crashed node's commands are delivered;
      - with the singleton's own node crashed straight after it resumed them,
        they are still delivered;
      - the journal shows no conflict;
      - the drained producers are gone from the list.
- [ ] **`spec-0099-bank`** — lark-bank drops its hand-built adoption and uses
      this. Done when: its `CrashTest` passes unchanged. This one waits for
      0094's stack to merge.

## Acceptance

```bash
./gradlew :lark-cluster:test --tests '*Producers*' :lark-bank:test
```

## Open questions

1. **On by default for `reliable(durable = true)`, or opt in?**
   Recommended: on. A durable producer whose commands wait for a node that
   never returns keeps only half of 0085's promise.
2. **A singleton, or each node deciding by itself?** Recommended: the
   singleton, as above.
3. **Where does a resumed producer run: on the singleton's node, or spread
   out?** Recommended: on the singleton's node. Resumed producers only drain,
   and they stop once drained, so their load is short-lived.
4. **Should the life in the id use 0097's `Cluster.uid`?** Recommended: yes.
   It is the life the membership already knows, so the registry and the
   member events agree on it.
