# 0072 — Events that outlive the node

## Problem

0063's `Journal` has one implementation, `InMemoryJournal`, so a persistent
actor's events last only as long as its process. 0070 then moves entities
between nodes and says "0063's journal restores its state", but an entity
moved to another node reads that node's own memory and starts from `empty`.
A service that runs persistent entities on a cluster today writes its own
`Journal` over its database, and has to get the one hard part right itself:
two writers for one id must never both succeed, even on two nodes, which is
exactly what a handoff that goes wrong would produce.

## Not doing

- **Snapshots.** Recovery still replays every event. Snapshots come when a
  recovery is measured to be slow.
- **Projections, or reading the journal as a stream.** Their own spec if asked.
- **A Kafka or Cassandra journal.** JDBC first; the contract tests below are
  what any later one must pass.
- **Creating the table.** The module ships its DDL, and the service applies it
  with whatever it already migrates with. Nothing runs DDL at start.
- **A connection pool, or a driver.** The `DataSource` is the service's, as in
  `lark-app-liquibase`.

## Shape

```kotlin
// lark-actor-journal-jdbc
flock<Nothing, Unit> {
    journal(JdbcJournal(dataSource))           // a table every node reaches
    val cluster = cluster(node("shop-1", 25520), seeds)
    val orders = cluster.sharding("order", OrderCodec, passivateAfter = 2.minutes) { id -> order(id) }
    …
}
```

```sql
-- lark/journal/jdbc/postgres.sql, shipped in the jar beside h2.sql
create table lark_journal (
    kind   varchar(255) not null,
    id     varchar(255) not null,
    seq_nr bigint       not null,
    bytes  bytea        not null,   -- blob in h2.sql
    primary key (kind, id, seq_nr)
);
```

- **Append** inserts the events in one transaction, numbered after
  `expected`. The primary key decides between two writers: a duplicate key is
  not an error but the `JournalConflict`, with the last sequence number read
  back in the same transaction. No lock is held between a read and a write.
- **Read** selects one id's events in order, from a sequence number on.
- **Failure.** A database that cannot answer throws from the step, as 0063
  says a journal does, and supervision decides.
- **Contract.** A `JournalContract` suite in `lark-actor`'s test fixtures
  states what every journal does: order, `from`, conflicts, copies of the
  bytes, and two threads racing on one id where exactly one wins. The
  in-memory journal and the JDBC one both run it.

## Why this shape

Leaning on the primary key makes the conflict the database's job, which holds
across nodes with no lease and no lock, and costs nothing when there is one
writer, which is the normal case under sharding. The alternative is
`select max(sequence) … for update` before each insert: it gives the same
answer with a lock per append and dialect differences in how the lock is
taken. Recommended: the primary key. A table shipped as DDL rather than
created at start keeps lark out of the service's migrations.

## Stack

- [x] **`spec-0072-contract`** — `JournalContract` in `lark-actor`'s test
      fixtures, run against `InMemoryJournal`. Done when: the suite passes,
      and an in-memory journal that lets both racing writers win fails it.
      ([#176](https://github.com/matthewjones372/lark/pull/176))
- [x] **`spec-0072-jdbc`** — `lark-actor-journal-jdbc`: `JdbcJournal` and its
      DDL, running the contract on H2 in test scope. Done when: the contract
      passes, and the runtime classpath is `lark-actor` and the JDK alone.
      ([#177](https://github.com/matthewjones372/lark/pull/177))
- [x] **`spec-0072-moved`** — a sharded persistent entity keeps its state
      across a move. Done when: three nodes share one H2 database, an entity
      is fed on the node that owns it, that node leaves, and the entity
      answers from the next owner with every event it had.
      ([#178](https://github.com/matthewjones372/lark/pull/178))

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Conflict by primary key, or by a lock taken before the insert?**
  Recommended: the primary key, as above.
- **Ship the DDL, or create the table on first use?** Recommended: ship it as
  a resource per database, Postgres and H2 to start, and document it; a
  Liquibase changelog can wrap it later. The SQL `JdbcJournal` runs is the
  same on both.
- **Which databases does CI prove?** Recommended: H2 only, since the SQL is
  plain inserts and selects; Postgres by Testcontainers can come when a user
  reports a dialect gap, as it needs Docker in CI.
- **Where does the contract live?** Recommended: `lark-actor`'s test fixtures,
  so a service's own journal can run it too.

Decided (2026-09-26): every open question goes as recommended. The primary key
decides conflicts; the DDL ships as a resource per database, Postgres and H2,
and nothing creates the table at start; CI proves H2 only; and the contract
lives in `lark-actor`'s test fixtures.

Decided while building `spec-0072-contract`: the contract is an abstract JUnit
class a journal's test extends, published as `lark-actor`'s test fixtures, and
detekt reads test fixtures as it reads tests. A journal that lets two racing
writers both succeed is made to do so every time, by a barrier between its read
and its write, so the check that the contract catches it does not depend on
timing.

Decided while building `spec-0072-jdbc`: the primary key alone catches a writer
behind the journal but not one ahead of it, so an append inserts its first
event only where the event it expects to follow exists, and a writer ahead
inserts nothing. A duplicate key is told by SQL state `23505`, which Postgres
and H2 both give. Dropping the primary key from the H2 DDL fails the contract's
race.

Decided while building `spec-0072-moved`: nothing in `lark-cluster` changes. A
region's entities already take the flock's journal, so a shared one is all a
move needs; the test proves it, and fails with each node on its own
`InMemoryJournal`, where the moved entity answers from empty.
