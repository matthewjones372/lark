# 0097 — a node that restarts where it was

## Problem

A node that restarts at the same address is a new life of that node, and the
membership already knows it. Each life is an `Incarnation` with a random `uid`,
and `admit` downs the earlier life as the new one joins
(`MembershipTest`: "a node that restarts joins as a new member, and its
earlier life is downed and removed"). Everything above the membership still
names a node by its address alone, so the new life is mistaken for the
earlier one in two places:

- **`Cluster.self` is a `Node`, with no `uid`.** The new life hears
  `Downed(earlier life)` and `Removed(earlier life)`, and each event's
  `member.node` equals its own `cluster.self`. A subscriber that asks "was I
  downed?" gets yes. lark-bank asked exactly that and exited, and so does
  `lark-app-cluster`'s `whenDowned = exit` (spec 0096). In the bank's Docker
  run this was the loop: bank-1 restarted, joined, heard its predecessor
  downed, exited, and restarted again about every 30 seconds. Only restarting
  every node at once cleared it, because that left no earlier life in anyone's
  view. `Cluster.ready()` and `Cluster.stop()` match by address in the same
  way.
- **Watches end by address.** `Removed(earlier life)` calls `endWatches(node)`,
  and `RemoteNode` ends every watch whose address names that node. The
  earlier life is removed `stableAfter` after it was downed. By then the new
  life is `Up`, and other nodes watch its actors: its shards' entities and a
  singleton it hosts. Those watches end with a `Terminated` that no actor
  sent.

## Not doing

- **No change to the membership.** `Incarnation`, `admit` and the gossip
  already treat each life apart, and their tests stay as they are.
- **No uid in `Node` or in an actor's `Address`.** A node is still found by
  its address. Frames already carry an actor's own incarnation, so a message
  for an earlier life's actor is not delivered to its successor.
- **No persisted node identity.** A restart is a new life by design; a node
  that wants to be the same life across restarts is a different feature.
- **Shard hand-over messages.** `Release` and `Released` name a `Node`. An
  earlier life that died holding shards cannot answer, and the new life starts
  with nothing, so nothing is known to go wrong there. This spec adds a test
  for it rather than a change.

## Shape

```kotlin
// lark-cluster
val Cluster.uid: Long                         // this life's; the uid its Member carries
fun Cluster.isSelf(member: Member): Boolean   // member.node == self && member.uid == uid

// a subscriber
if (event is MemberEvent.Downed && cluster.isSelf(event.member)) exit()   // the earlier life's downing is not ours
```

`ready()` and `stop()` match this life by `isSelf`. The watches follow the
life, not the address:

- `Removed(life)` ends the watches on its address only when no later life is
  live there.
- The first time a later life at an address is seen, the watches still held on
  that address end. They can only be on an earlier life's actors, because
  nothing could have watched the new life's actors before it was known.

`lark-app-cluster`'s `whenDowned = exit` uses `isSelf`.

## Why this shape

The membership got identity right. The API just never passed it up. Adding
`uid` and `isSelf` to `Cluster` fixes every caller that compares against
`self`, with one call and no break: `self` keeps its type, and code that
compared addresses keeps compiling. The alternative is to make `Cluster.self`
a `Member` or to add a `uid` to `Node`. Either one breaks every caller, and a
uid in `Node` would reach the transport and actor addresses, where the
address alone is what finds a node. Ending watches when a later life is first
seen, rather than when the earlier one is removed, is earlier and still
correct: the process those watches pointed at is gone.

## Stack

- [x] **`spec-0097-self`**: `Cluster.uid` and `Cluster.isSelf`; `ready()` and `stop()` by life; `lark-app-cluster`'s exit uses it.
      Done when: in a three-node test, a node stopped without leaving and started again on the same port comes `Up`,
      a subscriber on it never sees `isSelf` true for a `Downed` member, and `whenDowned = exit` does not exit.
- [x] **`spec-0097-watches`**: watches end per life. A later life seen at an address ends the earlier life's watches; its removal ends none.
      Done when: a watch on an actor of the new life survives the earlier life's `Removed`, and a watch on the earlier life's actor
      ends as the new life joins.
- [x] **`spec-0097-guide`**: the cluster guide says what a restart at the same address is, and to compare with `isSelf`.
      Done when: the guide's membership example uses `isSelf`, and compiles in `GuideExampleTest`.

## Acceptance

```bash
./gradlew :lark-cluster:test :lark-app-cluster:test
```

With lark-bank on the snapshot, the Docker run's `scripts/demo.sh` restarts
bank-3 and it comes back `Up` once, not in a loop, without restarting the
others.

## Open questions

Each recommendation was taken. Settled while building:

- **`stop()` still treats earlier lives at this address as this node** when it asks whether the node is the only
  member left, so a node alone with its own downed predecessor stops at once, as it did.
- **Both tests fail on the code they replace.** With the address check, `lark-app-cluster`'s restarted node exits
  once. With watches ended by address, the earlier life's watch ends only at its removal, and the test's check that
  it ended while that life was still in the view fails.

1. **Should `Cluster` expose the whole `Member` for this life instead of `uid`?**
   Recommended: `uid` and `isSelf`. The view holds the `Member`, and its
   status changes. A `Member` held on `Cluster` would be a stale copy of it.
2. **Should `ready()` require this life to be the only live member at its address?**
   Recommended: no. `admit` downs the earlier life before it welcomes the new
   one, so the two are never live together in a view that has merged the
   welcome.
3. **Should a subscriber be told nothing about its own earlier lives?**
   Recommended: no. Other subscribers need `Downed` and `Removed` for the
   earlier life, and one subscriber stream should not differ by node. `isSelf`
   makes the question answerable.
4. **Should this ship as a fix to `lark-app-cluster` ahead of the rest?**
   Recommended: yes, as the first entry. `whenDowned = exit` is shipped and has
   this bug, so any application on it that restarts a node in place loops the
   way the bank did.
