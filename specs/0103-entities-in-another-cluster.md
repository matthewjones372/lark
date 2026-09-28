# 0103 — Entities in another cluster

## Problem

A service that runs in two datacentres, each taking writes, cannot use one Lark cluster across both. Membership
over a WAN is slow to converge, and a split between two sites is an even split, which `keepMajority` cannot
decide without a third site (spec 0069 left multi-datacentre work out for this reason). So each site runs a
cluster of its own. What is missing is a way for one cluster to reach the other's sharded entities. lark-bank
needs it for active/active: each account lives in one region, and a transfer between regions must credit an
account the other cluster owns. Today the only way would be the service calling itself over HTTP.

## Not doing

- **Shared membership.** The clusters never gossip, down, or watch each other; each sees only its own members.
- **Singletons, topics or rebalancing across clusters.** Each stays within its cluster.
- **Exactly-once across clusters.** Messages are told and asked as to a local entity: at most once, with the
  sender retrying. A `reliable` producer to another cluster's entities is a follow-up if a service needs one.
- **Discovery of which cluster owns an id.** The caller knows, for example from the id itself.

## Shape

```kotlin
// in north's flock: south's nodes, found as its own members are (spec 0096), but never joined
val south = cluster.federate("south", Discovery.static(Node.at("south-1:25520"), Node.at("south-2:25520")))

val southAccounts: Sharded<AccountMessage> = south.sharding(Kinds.ACCOUNT, AccountMessages)
southAccounts.entity("south-acc-7").ask<AccountMessage, AccountAnswer>(3.seconds) { AccountAsk(credit, it) }
```

- **Routing:** an entity ref tells an envelope to the kind's region, at `/user/sharding-<kind>` on one of the
  other cluster's nodes. That region routes it to the owner, as it does for its own members' envelopes, hops
  included. No new wire protocol: regions are already exposed on the remote transport.
- **Choosing a node:** the discovered nodes are tried in turn. One whose connection fails is skipped until
  `retryAfter`. With none reachable, a tell is a dead letter, and an ask fails at once with `Unreachable`.
- **Asks:** the reply address is this node's own, so the other cluster must be able to reach it. This is the same
  requirement as within one cluster, just across two networks.
- **Meters:** `lark.federation.sent{cluster}`, `lark.federation.unreachable{cluster}`.

## Why this shape

A proxy per kind is the smallest thing that keeps each cluster whole: nothing about one cluster's membership or
downing depends on the WAN. The alternative, one stretched cluster with regions as roles (spec 0083) and a
witness site, keeps a single view but makes every membership change a WAN operation. It also needs a third site
just to break ties. Pekko offers both (multi-DC cluster, and sharding proxies across clusters); the proxy is the
one that needs no witness.

## Stack

- [ ] **`spec-0103-federate`**: `Cluster.federate`, entity refs over another cluster's regions, node choice and
      `Unreachable`, meters.
      Done when: two clusters in one JVM ask each other's entities; with the other cluster stopped, an ask fails
      within its timeout and a tell is a dead letter; once it is back, asks succeed without a restart.

## Acceptance

```bash
./gradlew :lark-cluster:check
```

## Open questions

1. **Should the other cluster's nodes be found by Discovery (static, DNS, Kubernetes) as members are?**
   Recommended: yes, the same `Joining` backends as spec 0096, from config: `federation.south.join = dns`.
2. **TLS between clusters?** Recommended: required outside tests. The remote transport already does TLS, as
   `TlsClusterTest` shows; spec it as the default for federated clusters.
3. **Should a region refuse envelopes from outside its cluster unless configured to accept them?**
   Recommended: yes, with an allow-list of cluster names, so a stray cluster cannot write to entities.
