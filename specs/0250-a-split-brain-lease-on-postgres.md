# 0250 — A split-brain lease on Postgres

## Problem

`Downing.lease` breaks an even split, or a two-node cluster, with a lock held outside the cluster. Surefold ships two:
`Kubernetes.lease` and `Aws.dynamoLease`. A service on ECS whose only datastore is Postgres has to add a DynamoDB
table, an IAM grant and a second store to reason about, just for one row, or write its own `Lease` against its own
table, untested against Surefold's downing contract.

## Not doing

- **Advisory locks.** A session-level `pg_try_advisory_lock` belongs to one connection, not to a side's `holder`
  string, so every node of a side could not renew it, and a pooled connection or RDS Proxy would lose it.
- **A database other than Postgres.** The SQL is `insert ... on conflict`; another dialect can bring its own `Lease`.
- **Changing `Downing`, `Lease` or how a side decides.** This is a third `Lease`, nothing more.
- **A driver or pool.** The caller passes a `javax.sql.DataSource`, as `surefold-jobs` and the journal do.

## Shape

A new module, `surefold-cluster-jdbc` (in `cluster/`), on `surefold-cluster` and the JDK only:

```kotlin
// One row per lease name; a holder keeps it for holdFor from its last acquire, and only it renews it.
val lease: Lease = JdbcLease(dataSource, name = "shop-split-brain", holdFor = 15.seconds)

val joining = Joining(Aws.ecs(EcsClient.create(), "shop-prod", "api", 25520), Downing.lease(lease))
```

`acquire(holder)` runs one statement and is true when it changed a row:

```sql
insert into surefold_cluster_leases (name, holder, expires_at) values (?, ?, ?)
on conflict (name) do update set holder = excluded.holder, expires_at = excluded.expires_at
 where surefold_cluster_leases.holder = excluded.holder or surefold_cluster_leases.expires_at < ?
```

An `SQLException` answers false, as `DynamoLease` answers false on an `SdkException`. The table ships as a Liquibase
formatted-SQL changelog at `surefold/cluster/jdbc/postgres.sql`, as the journal's does (spec 0107):
`surefold_cluster_leases (name text primary key, holder text not null, expires_at timestamptz not null)`.

## Why this shape

It is `DynamoLease` with the conditional put written as a conditional upsert: the same contract (free or mine, else
false), so `Downing` needs nothing new, and `AwsSuite`'s lease test (first holds, asking again renews, passes on once it lapses) carries over unchanged. A lease row survives
pooling, RDS Proxy and failover, unlike an advisory lock. The alternative, a `Lease` per service, is what each
service does today, untested.

## Stack

- [ ] **`cluster-jdbc-lease`**: the module, `JdbcLease`, the changelog, `docs/modules.md` and the new-module checklist.
      Done when: a Testcontainers Postgres suite shows two holders racing for a free lease get it once, the holder
      renews it, another takes it only after `holdFor`, and a closed database answers false.
- [ ] **`cluster-jdbc-downing`**: `DowningSuite`'s lease scenario run with a real `JdbcLease` in place of its fake
      `OneHolder`. Done when: of an even split, only the side whose lowest member holds the row stays.
- [ ] **`cluster-jdbc-guide`**: `docs/cluster.md` gains the ECS-with-Postgres recipe, as a compiled fence.
      Done when: `GuideExampleTest` compiles the fence (`surefold-cluster-jdbc` as its test dependency).

## Acceptance

```bash
./gradlew :surefold-cluster-jdbc:check
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Whose clock.** Recommended: the bound Surefold clock, as `DynamoLease` uses, so tests move time with the test
   clock; ECS tasks keep time with Amazon Time Sync. Alternative: the database's `now()`, which is immune to node
   skew but cannot be moved by a test.
2. **The database is down.** Then every side answers false and every side downs itself, so the whole cluster leaves.
   Recommended: accept it and say so in the guide. A service whose every request needs Postgres is down anyway.
   Alternative: keep the last answer for `holdFor`, which risks two sides staying.
3. **Config.** `join = ecs` needs `table` for DynamoDB today. Recommended: `cluster(...)` in `surefold-app-cluster`
   takes an optional `lease: Lease` that replaces the backend's, so `ecs.table` becomes optional when one is given.
   Alternative: a `lease = jdbc` config key that finds a `DataSource` in the graph, which is more magic.
4. **Flyway too.** `surefold-jobs` ships a `flyway/` copy as well. Recommended: Liquibase only, as the journal does
   since 0107, until a service asks for Flyway.
