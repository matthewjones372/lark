# 0069 — Nodes that agree who is up

## Problem

0068 lets a flock reach an actor on another node, but only at an address
someone gave it, and a node that goes away is noticed only as a connection
timing out. Sharding (0070) needs more: every node must agree which nodes are
members, so that all of them place an entity in the same place, and a node cut
off from the rest must stop acting before the rest take over its work. Today
that means Pekko Cluster, or ZooKeeper, etcd or Consul run beside the service.

## Not doing

- **An external coordinator.** No ZooKeeper, etcd or Consul in the core. A
  platform can still break a split through a `Lease` (below).
- **Sharding and singletons.** They are 0070, built on the member events here.
- **A gRPC or HTTP gateway** for callers that are not lark. Later, on its own.
- **Multi-datacentre awareness.** One cluster is one network.

## Shape

```kotlin
flock<Nothing, Unit> {
    val cluster = cluster(
        node("orders-1", port = 25520),
        discovery = Discovery.dns("orders.default.svc.cluster.local", port = 25520),
        downing = Downing.keepMajority(stableAfter = 20.seconds),
    )
    cluster.subscribe(listener)      // MemberUp, Unreachable, Reachable, MemberRemoved
    cluster.members                  // the agreed view: address, status, when it joined
}
```

- **Discovery** finds seed nodes and nothing more. `Discovery.static(…)` and
  `Discovery.dns(…)` (A/AAAA or SRV) are in the core; they cover a Kubernetes
  headless service and ECS Service Connect or Cloud Map. `lark-cluster-kubernetes`
  (the pods API) and `lark-cluster-aws` (Cloud Map and ECS APIs) are optional.
- **Membership** is gossip, as SWIM does it, over 0068's transport: each node
  probes a random member each round, asks others to probe one that does not
  answer, and spreads what it learns with every message. A member is `Joining`,
  `Up`, `Leaving`, `Down` or `Removed`; reachability is separate, as each node
  observes it. Joining asks a seed; the oldest reachable member moves joiners
  to `Up` once the view has converged.
- **Split brain** is decided by `Downing`, below. 0068's `watch` across nodes
  answers `Terminated` when membership removes the node, not on a timer.
- **Tests** run several nodes in one JVM, and cut and heal links between them
  through the transport, so a partition is a test step, not a guess at timing.

## Split brain

A partition leaves each side seeing the other as unreachable. If both carried
on, both would run the same singleton and the same entities. lark resolves it
the way Pekko's split brain resolver does:

1. **Wait for a stable view.** Nothing is decided until reachability has not
   changed for `stableAfter`, so a slow node or a GC pause does not split the
   cluster.
2. **Each side decides alone, and they agree without talking.** Every node
   applies the same rule to the same last agreed membership:
   - `keepMajority` (the default): the side with more than half of the members
     stays, and the other downs itself. On a tie, the side with the lowest
     address stays.
   - `staticQuorum(n)`: a side with at least `n` members stays.
   - `lease(lease)`: the side that acquires a `Lease` stays. It is for two
     nodes or an even split, where a majority cannot decide. `lark-cluster-kubernetes`
     ships a Kubernetes `Lease`, and `lark-cluster-aws` a DynamoDB one.
3. **The losing side stops first.** It downs itself at once: its flock stops
   its cluster actors and leaves. The winning side waits `stableAfter` again
   before it removes the unreachable members and takes over their work, so
   the two never overlap.
4. **A downed node never comes back as itself.** It must restart and rejoin,
   and 0068's handshake carries a uid for each life, so its old incarnation is
   refused.
5. **Persistence fences what is left.** 0063's journal refuses an append that
   does not follow the last one, so even an overlap that got through could not
   write two histories for one entity.

## Why this shape

Gossip needs nothing beside the service, which is the point of not using
ZooKeeper. Kubernetes knows which pods are scheduled, not which can reach each
other, so it is a place to find seeds and to hold a lease, not the membership
itself. Keeping discovery, membership and downing apart lets a user on ECS,
Kubernetes or plain VMs change only the first and the last. The alternative is
a consensus protocol (Raft) for membership: it gives a stronger view, but a
minority side cannot even learn it has lost, and it is far more code.
Recommended: gossip, with downing deciding splits.

## Stack

- [x] **`spec-0069-discovery`** — `Discovery`, static and DNS. Done when: DNS
      answers from a test server become seed nodes.
      ([#160](https://github.com/matthewjones372/lark/pull/160))
- [x] **`spec-0069-gossip`** — SWIM probing, indirect probes, gossip of member
      state, join through a seed, as a protocol with no thread or clock of its
      own. Done when: five nodes on a simulated network agree on the same view,
      and a stopped one is seen unreachable by all.
      ([#161](https://github.com/matthewjones372/lark/pull/161))
- [x] **`spec-0069-gossip-wire`** — the protocol as an actor on 0068's
      transport, and `cluster(…)`. Done when: five nodes in one JVM agree on the
      same view, and a stopped one is seen unreachable by all.
      ([#162](https://github.com/matthewjones372/lark/pull/162))
- [x] **`spec-0069-events`** — member events, and `watch` across nodes on
      membership. Done when: a removed node ends every watch on it.
      ([#163](https://github.com/matthewjones372/lark/pull/163))
- [x] **`spec-0069-downing`** — `keepMajority`, `staticQuorum`, `lease`, and
      the losing side downing itself. Done when: a 3–2 partition leaves the
      three up and the two stopped, with no moment where both sides are up.
      ([#164](https://github.com/matthewjones372/lark/pull/164))
- [x] **`spec-0069-kubernetes`** — `lark-cluster-kubernetes`: discovery from
      the pods API and a `Lease` object. Done when: tested against a fake of
      its API.
      ([#165](https://github.com/matthewjones372/lark/pull/165))
- [ ] **`spec-0069-aws`** — `lark-cluster-aws`: discovery from Cloud Map and
      ECS, and a DynamoDB lease. Done when: tested against a fake of its API.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Its own module (`lark-cluster`) on top of `lark-actor-remote`?**
  Recommended: yes.
- **Default `stableAfter`?** Recommended: 20 seconds, as Pekko's, and shorter
  in tests.
- **Does the losing side stop the whole flock, or only its cluster actors?**
  Recommended: only what the cluster started, and a member event the service
  can act on; ending the process is the service's decision.
- **Who moves a joiner to `Up`?** Recommended: the oldest reachable member,
  once every reachable member has seen the joiner.

Decided (2026-09-26): every open question goes as recommended. The cluster is
its own module, `lark-cluster`, on top of `lark-actor-remote`; `stableAfter`
is 20 seconds by default; a losing side stops what the cluster started and
tells the service, which decides whether the process ends; and the oldest
reachable member moves a joiner to `Up` once every reachable member has seen it.

Decided while building `spec-0069-discovery`: a seed is a node known only by
host and port, with an empty name, and the transport connects to whichever node
answers there. DNS goes through a `Resolver`, and the test server is a fake one,
since the JDK has no DNS server to run in a test; the JDK resolver is tested
against localhost.

Decided while building `spec-0069-gossip`: the protocol is a value with no
thread or clock, so partitions are tested on a simulated network, and the
entry is split so the transport comes separately. The lowest seed forms the
cluster only after `formAfter` with no other seed letting it in; the gossip
carries the node that formed the cluster, so two clusters never merge; and
convergence is every live member reporting the same digest of the members.

Decided while building `spec-0069-events`: the events are nested,
`MemberEvent.Up`, `.Unreachable`, `.Reachable` and `.Removed`; a subscriber is
an actor, told the view as it is before any change; and a node hands its
watches to the cluster through `RemoteNode.takeOverWatches()`, after which no
timer ends them.

Decided while building `spec-0069-downing`: a node decides only once it has
heard, since the view last changed, from every member it counts on its side,
since a partition found one probe at a time otherwise holds still halfway and
is decided on half of it. A side that goes downs all of itself and says so to
its members. A lease is asked for under the side's lowest address, so the whole
side gets one answer. The partition tests run on the simulated network.

Decided while building `spec-0069-kubernetes`: the fabric8 client, on the JDK's
own HTTP client rather than Vert.x, and its mock API server as the fake. The
lease is written with the version it read, so a lost race is the API server's
refusal rather than a second holder.
