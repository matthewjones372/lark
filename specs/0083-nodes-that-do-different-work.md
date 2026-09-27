# 0083 — Nodes that do different work

## Problem

Since 0070, every `Up` member hosts shards of every kind and may host every
singleton. A service whose nodes are not alike cannot say so. Examples:
- a few nodes with the database credentials, and the rest serving HTTP;
- a node pool with more memory for one kind of entity;
- a batch node that must never own a customer's entity.

Today such a service runs separate clusters and bridges them itself, or
accepts that every node does everything. Pekko's answer is roles: each node
names what it is for, and sharding and singletons are told which role may
host them.

## Not doing

- **Data centres.** Roles say what a node does, not where it is; placing by
  location, and gossip that is cheaper within one, are their own spec.
- **Changing a node's roles while it runs.** Roles are given when the node
  joins and kept until it leaves; a node that should do different work
  restarts, as a rolling deploy does.
- **Weights or capacities.** A member with the role is as likely as any other
  to own a shard.
- **Topics by role.** A topic (0082) reaches every member; a subscriber that
  does not want a message does not subscribe.

## Shape

```kotlin
flock<Nothing, Unit> {
    val cluster = cluster(node("db-1", 25520), seeds, roles = setOf("ledger"))
    val ledgers = cluster.sharding("ledger", LedgerCodec, passivateAfter = 2.minutes, role = "ledger") { id -> ledger(id) }
    val clock = cluster.singleton("clock", ClockCodec, role = "ledger") { clock() }
}
// a node without the role runs the same two calls, and routes to the ledger nodes without hosting any
```

- **Roles travel with membership.** A member's roles are part of its entry in
  the gossip, set on joining; `Member.roles` shows them in every view.
- **Placement filters by role.** `sharding(…, role = r)` places each shard on
  an `Up` member with role `r`, by the same rendezvous hash as now, so a shard
  moves only when a member with the role joins or goes. `singleton(…, role =
  r)` runs on the oldest `Up` member with it. With no role given, every member
  hosts, as now.
- **Members without the role route.** Their region still takes messages and
  passes them to the owner, as a region on a node that owns no shard of a
  kind does today. With no member holding the role, messages are kept, up to
  the region's bound, until one joins.
- **Readiness.** `Cluster.ready()` is unchanged: a node with no ledger role is
  ready while no ledger node is up, since its own work does not depend on it.

## Why this shape

Filtering the members a placement sees keeps everything else as it is. The
handoff, the buffering and the metrics all work over whichever members hold
the role, and a service that names no role sees no change. The alternative is
separate clusters per role, bridged by the service. That isolates failures
better, but loses one membership and one view, and every cross-role message
needs its own transport. Recommended: roles in one cluster.

## Stack

- [x] **`spec-0083-gossip`** — `roles` on `cluster(…)`, carried in the gossip
      and shown as `Member.roles`. Done when: three nodes with different roles
      each see every member's roles, and a node that restarts with other roles
      shows the new ones.
      ([#224](https://github.com/matthewjones372/lark/pull/224))
- [x] **`spec-0083-placement`** — `role` on `sharding` and `singleton`. Done
      when: of four nodes, two with the role, 500 entities spread over the two
      only and are reached from all four; a singleton runs on the older of the
      two; and with both gone, messages are kept and delivered once one with
      the role joins.
      ([#225](https://github.com/matthewjones372/lark/pull/225))

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Roles in one cluster, or a cluster per role?** Recommended: one cluster,
  as above.
- **Can a node hold several roles?** Recommended: yes, a set; a node may be
  both "ledger" and "web".
- **Should sharding take a set of roles (any of them) or one?** Recommended:
  one; a service that wants either names a role both kinds of node hold.
- **The gossip's wire format changes.** 0069's codec is internal, and nodes of
  different lark versions are not promised to join. Recommended: change it in
  place.

Decided (2026-09-27): every open question goes as recommended. Roles live in
one cluster; a node may hold several; sharding and singletons take one role;
and the gossip's internal codec changes in place.

Decided while building `spec-0083-gossip`: a node's roles travel in its
incarnation, since they are fixed for its life and the incarnation is already
written wherever a member is named. A node started again with other roles is
a new incarnation, like any restart, so its roles need no merging. `Member.roles`
defaults to none, so a `Member` written before this still compiles. A codec
that drops the roles fails the test.

Decided while building `spec-0083-placement`: a role only narrows the members
a placement sees; the handoff, the keeping and the routing are 0070's
unchanged. A member without the role is still asked to release a shard, and
answers at once, since it never holds one. `role` sits before the trailing
lambda on `sharding` and `singleton`, so every existing call compiles. The
test's last part asks from a node while no member has the role, starts one
that has it, and gets the answer from it; placement that ignores the role
fails the test's first part.
