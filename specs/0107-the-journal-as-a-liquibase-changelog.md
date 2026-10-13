# 0107 — The journal's tables as a Liquibase changelog, on Postgres only

## Problem

`lark-actor-journal-jdbc` ships its tables as plain DDL, `postgres.sql` and `h2.sql`, plus a file per change since
(`postgres-0105.sql`, `postgres-0106.sql`). A service applies them with its own migrations, which in practice means
Liquibase's `sqlFile` over `postgres.sql`. That breaks twice over:
- each change to `postgres.sql` changes the checksum of a changeset that has already run, so Liquibase refuses to
  start against every database made before it;
- the incremental files have to be found, ordered and added by hand, once per change.

The tests run mostly on H2, which is not what any service runs: the contracts run twice, once per database, and
H2's gaps (no transaction snapshots) get code paths of their own. The string columns are `varchar(255)`, a limit
nothing asked for.

## Not doing

- **A Liquibase dependency in the journal.** Its runtime classpath stays the JDK's (`NoOtherDependenciesTest`). The
  changelog is a resource; Liquibase is the service's.
- **Removing the paths for a database without transaction snapshots.** They stay, untested by lark, for any
  database a service brings.
- **Rewriting a service's own changelogs.** Lark's is included from them.

## Shape

`postgres.sql` becomes a Liquibase changelog in formatted SQL, one changeset holding every table; `h2.sql` goes.
No journal has been deployed, so nothing is carried over: no changeset per past spec, no `if not exists`, and no
conversion of earlier columns. `lark_journal.slice` is `not null`, so `JdbcJournal.fillSlices` and the checks for
rows without a slice go with it.

```sql
--liquibase formatted sql

--changeset lark:journal
create table lark_journal (kind text not null, id text not null, ..., slice integer not null, ...);
create index lark_journal_kind_slice on lark_journal (kind, slice, ordering);
-- and the pruned, fenced, slices, moves, snapshot and offset tables
```

A service includes it from its own changelog, `<include file="lark/journal/jdbc/postgres.sql"/>`, or runs it
through `lark-app-liquibase`: as a graph node (`migrations(...)`, as before) or now (`migrate(source, changelog)`).

A later change to the tables is a new changeset after `lark:journal`, never an edit to it: Liquibase refuses a
changelog whose applied changesets have changed.

Every test that needs a database runs on Postgres in a container (Testcontainers), through one fixture,
`lark-actor-journal-jdbc`'s `Postgres`: `fresh()` with the changelog applied, `empty()` without. The benchmarks
start theirs the same way. The sample bank keeps its journal in memory by default, and on Postgres with `--jdbc`.

## Why this shape

One changeset is the whole schema as it stands, readable in one place; changes from here on add changesets, so
no checksum of one that has run changes. Formatted SQL keeps the DDL readable as DDL, where XML or YAML changelogs would not. One database for the tests is
the one services run; a container, not an embedded binary, is the same Postgres they deploy.

## Stack

- [x] **`liquibase-changelogs`** — the changelog, the 0105 and 0106 files folded into it, `text` for strings,
      `migrate` in `lark-app-liquibase`, and H2 gone: every test, benchmark and the sample bank on Postgres in a
      container, the changelog applied by Liquibase.
      Done when: a fresh database takes the changelog once, then nothing, with every string column `text`.
- [x] **`one-changeset`** — the changesets collapsed into `lark:journal`, `slice` not null, and `fillSlices` and the
      unsliced checks gone.
      Done when: `./gradlew build` passes with no journal code or test that expects a row without a slice.

## Acceptance

```bash
./gradlew build
```
