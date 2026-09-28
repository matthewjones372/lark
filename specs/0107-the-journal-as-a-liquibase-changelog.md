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

`postgres.sql` becomes a Liquibase changelog in formatted SQL, with a changeset per change; `h2.sql` goes.

```sql
--liquibase formatted sql

--changeset lark:journal
create table if not exists lark_journal (kind text not null, id text not null, ...);

--changeset lark:0105-slices
alter table lark_journal add column if not exists slice integer;

--changeset lark:0106-kind-slice runInTransaction:false
create index concurrently if not exists lark_journal_kind_slice on lark_journal (kind, slice, ordering);

--changeset lark:text
alter table lark_journal alter column kind type text, alter column id type text;
```

A service includes it from its own changelog, `<include file="lark/journal/jdbc/postgres.sql"/>`, or runs it
through `lark-app-liquibase`: as a graph node (`migrations(...)`, as before) or now (`migrate(source, changelog)`).

Every changeset is written `if not exists`, so a database made from the plain DDL, before the changelog existed,
takes the changelog up by running each changeset as a no-op. `lark:text` turns the old `varchar(255)` columns into
`text`, which Postgres does in its catalogue alone.

Every test that needs a database runs on Postgres in a container (Testcontainers), through one fixture,
`lark-actor-journal-jdbc`'s `Postgres`: `fresh()` with the changelog applied, `empty()` without. The benchmarks
start theirs the same way. The sample bank keeps its journal in memory by default, and on Postgres with `--jdbc`.

## Why this shape

Changesets that only add mean a new table or index is a new changeset, and no checksum of one that has run
changes. Writing each `if not exists` is simpler than a precondition per changeset for adopting existing databases.
Formatted SQL keeps the DDL readable as DDL, where XML or YAML changelogs would not. One database for the tests is
the one services run; a container, not an embedded binary, is the same Postgres they deploy.

## Stack

- [x] **`liquibase-changelogs`** — the changelog, the 0105 and 0106 files folded into it, `text` for strings,
      `migrate` in `lark-app-liquibase`, and H2 gone: every test, benchmark and the sample bank on Postgres in a
      container, the changelog applied by Liquibase.
      Done when: a fresh database takes four changesets, then none, with every string column `text`; and a journal
      made by the DDL from before 0105, with `varchar`, takes the changelog up, keeps its events, fills their slices
      and ends with `text`.

## Acceptance

```bash
./gradlew build
```
