# 0022 — A migration is a step

## Problem

A service runs its migrations in `main`, before it builds anything that reads
the database, and remembers to keep it that way. The ordering is real and the
graph does not know it: a repository takes a `DataSource`, the `DataSource` is
there before Flyway has run, and nothing stops a node being added that reads a
table that does not exist yet. The rule lives in a comment and in the order two
statements happen to be written in.

## Not doing

- **No Flyway.** One first; the second is the same shape if anyone wants it.
- **No driver and no `DataSource`.** Both are the application's, and so is what
  they connect to.
- **No rollback, no diff, no snapshot.** Liquibase has a CLI for those, and a
  process that rolls itself back on start-up is a worse outage than the one it
  is avoiding.

## Shape

A module, `lark-app-liquibase`, on `lark-app` and `liquibase-core`.

```kotlin
val database: Module =
    single { cfg: DbConfig -> install({ Hikari(cfg) }) { pool, _ -> pool.close() } as DataSource } +
    migrations("db/changelog.xml") +
    single { db: DataSource, _: Migrated -> PgUserRepo(db) as UserRepo }
```

- `migrations(changelog, contexts, labels): Module`, providing `Migrated`.
- `Migrated.applied` — how many changesets ran, which is the deploy's log line.
- The connection is taken from the `DataSource` and given back before the node
  answers.

## Why this shape

A marker type rather than a `runMigrations()` call at the top of `main`. Taking
a `Migrated` is what makes the ordering an edge: the reader cannot be built
first, `validate` refuses a graph that forgot it, and `render` draws it for a
reviewer. Nothing else about the node is unusual — it is a value built once,
like any other.

The alternative — a `beforeStart` hook on the runner — would order the
migration against everything rather than against the things that read the
database, and would start the broker and the HTTP server behind it for no
reason.

## Stack

- [x] **`spec-0022-liquibase`** — the module, `migrations`, `Migrated`, and the
      dependency test.
      Done when: a reader takes a `Migrated` and cannot be built before the
      changelog has run, and running it twice applies nothing the second time.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `Migrated` belong in `lark-app` so a Flyway module could provide the
   same key?** Recommend not yet: two implementations of one marker is a
   decision to make when the second exists, not before.
2. **Should the node be able to refuse rather than throw when a changelog
   fails?** Recommend throwing. A failed migration is not a declared outcome a
   caller chose between; it is the deploy stopping.
