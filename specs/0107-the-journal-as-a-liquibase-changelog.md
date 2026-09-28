# 0107 — The journal's tables as a Liquibase changelog

## Problem

`lark-actor-journal-jdbc` ships its tables as plain DDL, `postgres.sql` and `h2.sql`, plus a file per change since
(`postgres-0105.sql`, `postgres-0106.sql`). A service applies them with its own migrations, which in practice means
Liquibase's `sqlFile` over `postgres.sql`. That breaks twice over:
- each change to `postgres.sql` changes the checksum of a changeset that has already run, so Liquibase refuses to
  start against every database made before it;
- the incremental files have to be found, ordered and added by hand, once per change.

## Not doing

- **A Liquibase dependency in the journal.** Its runtime classpath stays the JDK's (`NoOtherDependenciesTest`). The
  changelogs are resources; Liquibase is the service's.
- **Other migration tools.** Flyway and the rest can still read the SQL, but only Liquibase is tested.
- **Rewriting a service's own changelogs.** Lark's changelogs are included from them.

## Shape

`postgres.sql` and `h2.sql` become Liquibase changelogs in formatted SQL, with a changeset per change:

```sql
--liquibase formatted sql

--changeset lark:journal
create table if not exists lark_journal (...);

--changeset lark:0105-slices
alter table lark_journal add column if not exists slice integer;

--changeset lark:0106-kind-slice runInTransaction:false
create index concurrently if not exists lark_journal_kind_slice on lark_journal (kind, slice, ordering);
```

A service includes it from its own changelog:

```xml
<include file="lark/journal/jdbc/postgres.sql"/>
```

or runs it through `lark-app-liquibase`, as a graph node (`migrations(...)`, as before) or now (`migrate(source,
"lark/journal/jdbc/postgres.sql")`).

Every changeset is written `if not exists`. So a database made from the plain DDL, before the changelog existed,
takes the changelog up by running each changeset as a no-op.

## Why this shape

Changesets that only add mean a new table or index is a new changeset, and no checksum of one that has run
changes. Writing each `if not exists` costs nothing, and is simpler than a precondition per changeset for adopting
existing databases. The alternative was XML or YAML changelogs, but formatted SQL keeps the DDL readable as DDL.

## Stack

- [x] **`liquibase-changelogs`** — the two changelogs, the 0105 and 0106 files folded into them, `migrate` in
      `lark-app-liquibase`, and Liquibase applying them in every test, benchmark and the sample bank.
      Done when: a fresh H2 and a fresh Postgres each take three changesets, then none; and a Postgres journal
      made by the DDL from before 0105 takes the changelog up, keeps its events, and fills their slices.

## Acceptance

```bash
./gradlew build
```
