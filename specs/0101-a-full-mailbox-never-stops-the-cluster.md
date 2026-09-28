# 0101 — A full mailbox never stops the cluster actor

## Problem

The cluster actor tells two kinds of actor what changed: each region, with the new view, and each subscriber, with
the member events. A tell from a step to a full mailbox throws, and the throw stopped the cluster actor. That node
then kept serving, but its view never moved again. It could not hear itself downed, so `whenDowned = exit` never
ran, and it stayed out of the cluster until something restarted it. lark-bank's Docker chaos run found this. bank-2
was frozen for 20 s under load, and the others downed it. When it thawed, its account region's mailbox was full of
the messages that had queued behind the freeze. The next view it published threw "the mailbox of
/user/sharding-account is full", and bank-2 stayed Down, as a zombie, for the rest of the run.

## Not doing

- **No unbounded mailboxes.** A full mailbox is how backpressure works everywhere else in lark.
- **No supervision of the cluster actor.** A restart would lose its subscribers and gain nothing. The cause is
  removed instead.
- **No change to what subscribers hear, or in what order.**

## Shape

- **Subscribers:** the cluster actor hands events on with `HandOn` (spec 0095), keyed by subscriber. An event a
  subscriber has no room for is kept, in order, and the actor retries on each tick. A downed cluster actor stops
  only once every subscriber has been handed its events, so the Downed that ends the process is never dropped. A
  subscriber that stops is owed nothing more.
- **Regions:** a region only ever needs the newest view. The cluster actor sets it in the region's `latest` and
  tells a `Look` if there is room. Before every step, the region takes whatever view is waiting. The earlier rule
  "a region is told the view before anyone waiting on it wakes" still holds, and is stronger than before: a region
  whose mailbox is full still routes by the newest view.
- `ActorRef.tellIfRoom` becomes public, marked `@PlumbingSeam`, as `HandOn` already is.

## Why this shape

Views are values that replace each other, so conflating them loses nothing, and a region never falls behind the
view however busy it is. Member events are a history that subscribers act on (a log line, an exit), so they are kept
rather than conflated. The alternative is for the cluster actor to block until a subscriber has room, but an actor
must not block its runner, and the cluster actor is what gossip depends on.

## Stack

- [x] **`claude/full-mailbox-cluster`** — both rules, and a test of a subscriber whose mailbox is full.
      Done when: a subscriber of capacity 1 that is stuck on its first event does not stop the view moving, and
      hears every Up once it is free. Before the fix the test fails with "the mailbox of /user/slow is full".

## Acceptance

```bash
./gradlew :lark-actor:check :lark-cluster:check
```

## Open questions

1. **Should a cluster actor that stops for any other reason end the process?** Recommended: yes, as a follow-up.
   `lark-app-cluster` could watch it and treat an unexpected stop as being downed. Not done here, because this spec
   removes the only known cause.
2. **Should a subscriber past `KEEP_AT_MOST` events behind be dropped rather than lose events?** Recommended: no.
   10,000 member events is far past any real cluster, and a dead letter is what happens today.
