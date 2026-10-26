# 0103 — Entities in another cluster

## Problem

A service that runs in two datacentres, each taking writes, cannot use one Surefold cluster across both. Membership
over a WAN is slow to converge, and a split between two sites is an even split, which `keepMajority` cannot
decide without a third site (spec 0069 left multi-datacentre work out for this reason). So each site runs a
cluster of its own. What is missing is a way for one cluster to reach the other's sharded entities. surefold-bank
needs it for active/active: each account lives in one region, and a transfer between regions must credit an
account the other cluster owns. Today the only way would be the service calling itself over HTTP.

## Not doing

- **Shared membership.** The clusters never gossip, down, or watch each other; each sees only its own members.
- **Singletons, topics or rebalancing across clusters.** Each stays within its cluster.
- **Exactly-once across clusters.** Messages are told and asked as to a local entity: at most once, with the
  sender retrying. A `reliable` producer to another cluster's entities is a follow-up if a service needs one.
- **Discovery of which cluster owns an id.** The caller knows, for example from the id itself.
- **Different kind codecs on each side.** The remote transport agrees a wire version per connection, but an
  envelope's message is in the kind's own codec, which both clusters must read alike. A change to it reaches both
  clusters before either writes the new form, as a change to any codec within one cluster does.

## Shape

```kotlin
// in north's flock: south's nodes, found by a Discovery as its own seeds are (spec 0096), but never joined
val south = cluster.federate("south", Discovery.static(Node.at("south-1:25520"), Node.at("south-2:25520")), from = "north")
// and in south's, which takes envelopes only from the clusters it names
cluster.acceptFederated("north")

val southAccounts: Sharded<AccountMessage> = south.sharding(Kinds.ACCOUNT, AccountMessages)
southAccounts.entity("south-acc-7").ask<AccountMessage, AccountAnswer>(3.seconds) { AccountAsk(credit, it) }
```

- **Routing:** an entity ref tells an envelope to the kind's region, at `/user/sharding-<kind>` on one of the
  other cluster's nodes. That region routes it to the owner, as it does for its own members' envelopes, hops
  included. No new frame: regions are already exposed on the remote transport. What a region accepts does
  change, below.
- **A region takes only envelopes from outside its cluster.** `RegionCodec` also carries `Release` and
  `Released`, the shard handoff between a region and its peers, and today a region acts on them from any node
  that connects. A region now drops a `Release` or `Released` whose sender is not one of its own cluster's
  members, as a dead letter, so a federated cluster can reach entities but never move a shard. The sender is the
  node its connection's hello names, which only TLS ties to the peer (`Tls.check`); without TLS it is the peer's
  word.
- **Choosing a node:** the discovered nodes are tried in turn. One whose connection fails is skipped until
  `retryAfter`. With none reachable, a tell is a dead letter and an ask fails at once with `Unreachable`.
- **`Unreachable` is new work.** `AskFailure.Unreachable` is declared, but nothing returns it: a frame dropped
  for an unreachable peer is a dead letter (`Remote.kt`, `dropped`), and the ask waits out its timeout. The
  remote transport has to fail the ask whose frame it dropped, which helps asks within one cluster too.
- **Asks:** the reply address is this node's own, so the other cluster must be able to reach it. This is the same
  requirement as within one cluster, just across two networks.
- **TLS across clusters:** `Tls.check` requires a peer's certificate to name it, so both clusters' certificates
  are issued by a CA each trusts, which in practice means a CA they share.
- **Meters:** `surefold.federation.sent{cluster}`, `surefold.federation.unreachable{cluster}`.

## Why this shape

A proxy per kind is the smallest thing that keeps each cluster whole: nothing about one cluster's membership or
downing depends on the WAN. The alternative, one stretched cluster with regions as roles (spec 0083) and a
witness site, keeps a single view but makes every membership change a WAN operation. It also needs a third site
just to break ties. Pekko offers both (multi-DC cluster, and sharding proxies across clusters); the proxy is the
one that needs no witness.

## Stack

- [x] **`spec-0103-unreachable`** ([#757](https://github.com/matthewjones372/surefold/pull/757)) — an ask whose frame the remote transport drops as unreachable fails at once
      with `AskFailure.Unreachable`.
      Done when: an ask to an actor on a stopped node answers `Unreachable` well before its timeout.
      Each frame now carries the paths of the replies written into it for asks on this node. When the transport
      drops it, unsent because its connection is down or its queue is full, those asks end with `Unreachable`
      through `Unanswerable`, which the runtime's own ask implements. A tell has no reply to end, and stays the dead
      letter it was.
- [x] **`spec-0103-members-only`** ([#760](https://github.com/matthewjones372/surefold/pull/760)) — a region drops `Release` and `Released` from a node outside its cluster.
      Done when: a `Release` sent from a node that is not a member leaves the shard where it was and is a dead
      letter.
      `WireIn.sender` is the node whose connection a message came in on, as its hello named it; `RegionCodec`
      stamps it on a `Release` or `Released`, and the region drops one from a node not in its view's members as an
      `Unhandled` dead letter. One told on its own node has no sender and is taken. A member the region's view does
      not hold yet is refused too, and its `Release` is asked again on the winner's `AskAgain` timer.
- [x] **`spec-0103-federate`** ([#762](https://github.com/matthewjones372/surefold/pull/762)) — `Cluster.federate`, entity refs over another cluster's regions, node choice,
      meters.
      Done when: two clusters in one JVM ask each other's entities; with the other cluster stopped, an ask fails
      with `Unreachable` and a tell is a dead letter; once it is back, asks succeed without a restart.
      `federate(name, nodes, retryAfter)` answers a `Federated`, whose `sharding(kind, codec)` is a `FederatedKind`
      with `entity(id)` alone: not `Sharded`, whose `reliable` producers to another cluster are out of scope.
      `RemoteNode.failing(peer, within)` says a frame for a peer was dropped lately with no connection since; with
      every node failing the first is sent to anyway, so #757's drop answers the ask. A frame written into a
      connection the far side has closed, before this side sees it close, is lost rather than dropped, as at most
      once allows: that ask waits out its time, and only the next one fails at once.
- [ ] **`spec-0103-allow`** — a region refuses envelopes from another cluster unless that cluster is allowed
      (question 3), and `federate` makes TLS the default outside tests (question 2).
      Done when: a cluster not on the allow-list is refused, as a dead letter, and an allowed one is answered.
      `federate(name, nodes, from)` stamps `from` on each envelope, as `RegionCodec`'s `FEDERATED` tag; a region
      routes one only once `acceptFederated(from)` named it, and drops any other as an `Unhandled` dead letter. An
      envelope with no `from`, a member's own, is routed as before, so a member the view does not hold yet loses
      nothing. `federate` throws on a node without `Tls`, since only TLS ties a peer to the name it gives, unless
      `plaintext = true`, for tests.

## Acceptance

```bash
./gradlew :surefold-actor-remote:check :surefold-cluster:check
```

## Open questions

Answered (maintainer, 2026-10-26: go with the recommendations).

1. **Should the other cluster's nodes be found by Discovery (static, DNS, Kubernetes) as seeds are?**
   Recommended: yes, the same `Discovery` backends as spec 0096, from config: `federation.south.join = dns`. Only
   the `Discovery` half of a `Joining`: downing is the other cluster's own business.
2. **TLS between clusters?** Recommended: required outside tests. The remote transport already does TLS, as
   `TlsClusterTest` shows, and the sender check above means nothing without it.
3. **Should a region refuse envelopes from outside its cluster unless configured to accept them?**
   Recommended: yes, with an allow-list of cluster names, so a stray cluster cannot write to entities.
4. **A node that is up but whose region is not open yet?** Discovery does not see the other cluster's
   membership, so a federated sender can choose a node that its own cluster still holds `Joining` (spec 0129).
   The envelope is then a dead letter there, as spec 0129's were before it, and an ask times out. Recommended:
   accept it for now, since the sender retries, and revisit if the bank sees it.
