# 0129 — Nothing routed to a region that is not open yet

## Problem

A node is `Up` as soon as the leader sees it reachable, and lark-app joins the cluster before it opens any sharding
region, since a region needs the `Cluster`. In between, every other member places shards on the new node by
`Placement.owner` and routes envelopes to its region, which does not exist yet. `Remote.deliver` makes each one a
dead letter, `NoSuchActor`, as spec 0068 says a frame for a path with no actor is.

lark-bank sees it on every join. A probe that sends 40 traced transfers while a third node joins dead-lettered one or
two envelopes at `/user/sharding-account` or `/user/sharding-transfer` in each of 18 runs. A lost ask is a 503 to the
customer (about 1 in 40 requests during the join). A lost leg is sent again by the saga's timer, outside the transfer's
trace, since a timer carries nothing (spec 0122). That broke one transfer's trace in those 18 runs.

## Not doing

- **No change to dead letters for a path that never opens.** Spec 0068's rule stays for a frame with nowhere to go.
- **No trace on timers.** Spec 0122 decided a timer carries nothing; this spec removes the loss that makes a retry
  necessary, not the retry's context.
- **No buffering in `lark-actor-remote`.** See Why this shape.

## Shape

A member stays `Joining` until it says it is ready, and is `Up`, and so an owner, only then:

```kotlin
val cluster = cluster(node(...), seeds, gossiping, ready = Ready.WhenTold)  // default Ready.AtOnce, as today
// ... open every region, singleton and topic this node hosts ...
cluster.admit()                                                              // gossips that this life is ready
```

- `Ready.AtOnce` keeps today's behaviour for anyone calling `cluster` directly.
- The leader moves a `Joining` member to `Up` only once it is in the gossip's `admitted`: the lives that have said
  they are ready, a set that only grows, as a life is never unready again. The codec writes it in place, as spec 0083
  decided for every change to the gossip: nodes of different Lark versions are not promised to join.
- lark-app passes `Ready.WhenTold` and admits once every node is built. A probe is asked as soon as its node is
  built, so the cluster's (`Up`) would wait for an `admit()` that comes only after the regions that need the
  cluster. lark-app gains two things for it: `Wiring.onStarted { }`, run once every node is built, in the graph's
  order, and a probe that is `asked = Asked.WhenStarted`, after those. The cluster admits in `onStarted`, and its
  probe, still `ready()`, is asked when started, so `/ready` keeps meaning `Up`.
- Placement already counts only `Up` members for shards, spread workers and singletons (`Placement.owner`, `spread`,
  `oldest`), so a `Joining` member is given nothing until it is admitted.

## Why this shape

Three ways to close the window, recommended first:

1. **Up only when ready** (above): one rule covers regions, singletons and topics, and placement needs no change.
   Its cost is a joiner that never calls `admit()` stays `Joining`, which lark-app's readiness probe already reports.
2. **Members gossip which kinds they have open**, and `Placement.owner` counts only those for a kind. Exact per kind,
   and a region opened late still works, but every placement call and the gossip carry a set per member.
3. **Remote keeps frames for a path not exposed yet**, for a bounded time, and delivers them once it is. Small, but it
   holds frames for typos too, and a node that never opens the path turns a dead letter into a delay.

## Stack

- [ ] **`spec-0129-ready`**: the `ready` flag in member gossip, the leader's rule, `Ready`, `admit()`.
      Done when: a member made with `Ready.WhenTold` stays `Joining` in every view until `admit()`, then is `Up`,
      the one that forms the cluster included; a member made with `Ready.AtOnce` is `Up` as today.
- [ ] **`spec-0129-app`**: `Wiring.onStarted` and `Asked.WhenStarted` in lark-app; its cluster `Ready.WhenTold`,
      admitted in `onStarted`, with its probe asked when started.
      Done when: a test with a sharded kind, telling an entity on a node the moment a third joins, has no
      `NoSuchActor` dead letter for that kind's region.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **What counts as wired in lark-app?** Every `single` built, or only the cluster's dependents? Recommend every
   `single`: a node half wired should not take shards it cannot run.
2. **A rolling upgrade**: nodes of different Lark versions are not promised to join (spec 0083), so there is no
   old leader to promote a new joiner. Recommend leaving it at that.
3. **Option 2 later?** A region opened after `admit()` still has the window. Recommend leaving it until something
   opens regions lazily; nothing in the estate does.

Decided (2026-10-13): every open question goes as recommended.
