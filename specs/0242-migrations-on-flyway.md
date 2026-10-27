# 0242 — Migrations on Flyway

## Problem

A service on Surefold migrates with `surefold-app-liquibase`, and every data module ships its tables only as Liquibase
formatted SQL, so Liquibase is on every such service's classpath. The owner of Surefold and Doorlist wants Flyway
instead ("it's way better") and wants Doorlist rid of Liquibase entirely. On the merits: Doorlist's 51 changesets all
read `splitStatements:false` and use no rollback, precondition or context, so it pays for a changelog format and XML
master file and gets only an ordered list of SQL files, which is what Flyway is. Two gaps exist whichever tool runs:
`surefold-app-jobs` takes `Migrated` from `surefold-app-liquibase`, and `migrations()` runs on the graph's `DataSource`,
so a service that migrates as a schema-owner role (Doorlist's `Database.kt`) hand-rolls a one-connection pool and
its own `Schema` marker instead.

## Not doing

- No Flyway Teams features (undo, dry run, cherry-pick) and no Java-based migrations in the data modules.
- No removal of `surefold-app-liquibase` or of the modules' Liquibase changelogs; both stay supported.
- No running both tools on one database beyond the one-time handover below.
- No Doorlist changes: it moves itself once this ships.

## Shape

```kotlin
val database: Module =
    closingOf { cfg: DbConfig -> HikariDataSource(cfg) }.boundTo<DataSource>() +
    flyway(SurefoldSchema.TENANCY, SurefoldSchema.JOBS, SurefoldSchema.AUTH, service = "classpath:db/migration") +
    single { db: DataSource, _: Migrated -> PgUserRepo(db) as UserRepo }   // Migrated now from surefold-app

// As the schema owner, on a source opened for the migration and closed when it returns; the app never holds it.
flyway(SurefoldSchema.TENANCY, SurefoldSchema.JOBS, service = "classpath:db/migration",
    connect = { s: DatabaseSettings -> ownerPool(s) }) + …

val applied: Int = migrate(ownerSource, SurefoldSchema.JOBS, service = "classpath:db/migration")   // outside a graph
```

- **`Migrated`** moves to `surefold-app` with a public constructor; `surefold-app-liquibase` keeps a deprecated
  `typealias`. `surefold-app-jobs` then depends on `surefold-app`, so a Flyway service has no Liquibase on its classpath.
- **`connect`** takes one graph key and returns a `DataSource`, closed after if `AutoCloseable`. Without it the node
  takes the graph's `DataSource` and returns its connection, as today. `migrations(changelog, connect = …)` gets the same.
- **Module scripts**: each data module ships `surefold/<name>/flyway/V<n>__<changeset>.sql`, one per Liquibase
  changeset, plain SQL with no Liquibase header. Each module, and the service, has its own history table
  (`flyway_surefold_jobs`, …, then `flyway_schema_history`), run in `SurefoldSchema` order (tenancy, jobs,
  idempotency, ratelimit, auth) whatever the argument order, then the service's. Module and service versions never meet.
- **Pinned, not settable**: `cleanDisabled = true` (a `clean` drops the schema; no service wants that in production);
  `validateOnMigrate = true`, so an edited applied script fails start with its checksum; `baselineOnMigrate = false`,
  since it baselines any non-empty schema silently; `outOfOrder = false`, which per-module tables make unneeded;
  `placeholderReplacement = false`, so a `${` in a function body is SQL. **Defaults, settable**: `mixed = false`,
  since Postgres DDL is transactional and a script needing `create index concurrently` says so in its own
  `.sql.conf` with `executeInTransaction=false`; `postgresql.transactional.lock = false`, so such a script cannot
  wait on the lock's own transaction; `connectRetries = 0`, since a service that cannot reach Postgres should fail start
  and be restarted. Repeatable `R__` scripts are allowed for the service; the modules ship none.
- **A history table starts at a baseline of `0`**: Flyway refuses a non-empty schema that has no history table, which
  every table after the first meets, so one with nothing applied is baselined at `0` and then migrated. No script is
  skipped (a `V0` is refused), and Flyway's own refusal is replaced by `relation already exists` on a first script that
  meets existing tables, and by refusing a database with `databasechangelog` unless `fromLiquibase` is set.
- **From Liquibase**: `flyway(…, fromLiquibase = "doorlist:0014-live-updates")` baselines at `V1` only when there is no
  Flyway history and `databasechangelog`'s last row is that id, else refuses to start; modules baseline at the version
  matching their last applied changeset. `docs/flyway.md` takes a service through it: `V1` is a schema-only dump of
  today's schema, a test proves it equal to the changelog's, and a later release ships
  `V2__drop_liquibase.sql` dropping `databasechangelog` and `databasechangeloglock` (`if exists`).

## Why this shape

Per-module history tables keep a module's versions its own: a service on `V40` takes jobs' new `V7` next release
without `outOfOrder` or a reserved range. **Alternatives:** one table with module versions as `V0.<module>.<n>`,
which breaks the first time a module adds a script after the service's `V1`; and a Java migration per module, which
hides SQL behind code and puts a module's schema on a Flyway API. Both rejected. The cost is two copies of each
module's SQL, held equal by a test, not by generation; generating Flyway scripts from changelogs would put Liquibase
in the build. What Flyway Community loses: rollback blocks, preconditions, contexts and labels, and `runOnChange`
(an `R__` script covers the last). Nothing in Surefold's modules or Doorlist uses the others.

## Stack

- [x] **`spec-0242-migrated`** — `Migrated` in `surefold-app`, the alias, `surefold-app-jobs` and the compiler's name.
      Done when: `surefold-app-jobs`' `NoOtherDependenciesTest` allows no Liquibase and its suite passes unchanged.
- [x] **`spec-0242-flyway`** — `surefold-app-flyway`: `flyway`, `migrate`, `connect`, the pinned settings.
      Done when: on Testcontainers Postgres, a reader of `Migrated` starts after the scripts; a second run applies none;
      an edited applied script fails start naming its checksum; `clean()` throws; with `connect`, the tables are
      owned by the owner role and that pool is closed before `Migrated` is answered.
- [x] **`spec-0242-liquibase-connect`** — `connect` on `migrations`. Done when: the same owner-role test passes.
- [x] **`spec-0242-module-scripts`** — Flyway scripts in the five data modules, `SurefoldSchema`, the equality check.
      Done when: per module, the catalog (tables, columns, constraints, indexes, policies, grants, functions) after
      Flyway equals that after Liquibase; jobs listed before tenancy still runs after it.
- [x] **`spec-0242-from-liquibase`** — `fromLiquibase`, `docs/flyway.md`, cookbook and module tables.
      Done when: a database migrated by Liquibase is baselined and then dropped of Liquibase's tables, and one whose
      last changeset differs is refused; the guide's fences compile.

## Acceptance

```bash
./gradlew :surefold-app-flyway:build :surefold-app-jobs:build :surefold-app-liquibase:build :surefold-jobs:build
```

## Open questions

1. **Do the modules' Liquibase changelogs stay?** Recommended: yes, supported and not deprecated, until a release
   where every known service is on Flyway; the equality test makes the second copy cheap.
2. **`Migrated` in `surefold-app`, or a `surefold-app-migrations` module?** Recommended: `surefold-app`; it is one
   class with no dependency, and a module for it is ceremony.
3. **Should `surefold-actor-journal-jdbc` ship Flyway scripts too?** Recommended: yes, in a follow-up spec;
   it is not a data module and nobody on Flyway uses it yet.
4. **`connectRetries` default 0 or a few seconds?** Recommended: 0, as above; a parameter for those who want it.
