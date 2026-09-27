# 0078 — The journal on Postgres

## Problem

`lark-actor-journal-jdbc` ships DDL for Postgres and H2, and its tests run on
H2 alone. The parts most likely to differ between the two are also the parts
that matter most:
- the duplicate-key SQL state that tells one writer from two;
- `generated always as identity` and when an `ordering` is handed out;
- whether a row inserted by an open transaction is invisible to the feed or
  blocks it;
- the conditional insert that appends only after the event it expects.

A service on Postgres, which is where most of them are, runs code no test has
run on its database.

## Not doing

- **Other databases.** MySQL, Oracle and SQL Server keep no DDL here and get no
  tests; a user who needs one reports it, as 0072 said.
- **Postgres in the main build's dependencies.** The driver and the database
  are test scope; the module's runtime classpath stays `lark-actor` and the JDK.
- **Performance tests.** Correctness on Postgres only.

## Shape

```kotlin
class PostgresJournalTest : JournalContract() {
    override fun journal(): Journal = JdbcJournal(Postgres.fresh())   // an empty database with postgres.sql applied
}
```

- **`Postgres`**, in the module's tests, starts one Postgres for the test JVM
  with `io.zonky.test:embedded-postgres`. It is a real Postgres binary fetched
  from Maven Central and run as a child process, so it needs no Docker and
  runs the same on a laptop, here and in CI. `fresh()` creates a new database
  with `postgres.sql` applied, so tests stay independent.
- **Every contract runs on it:** `JournalContract`, `FeedContract`,
  `PruneContract`, `SnapshotContract` and `OffsetContract`, next to the H2
  classes that run them now.
- **The feed's gap tests run on it too**, with the open transaction on a
  second Postgres connection, so the claim that an uncommitted append holds
  the feed back is checked where it matters.

## Why this shape

Embedded Postgres is the real server, so it answers the questions H2 cannot,
and it needs nothing but Maven Central, which every build already reaches.
The alternative is Testcontainers, which is the more common choice and runs
any image. It needs a Docker daemon, which CI runners have and this project's
own development container does not, so the tests would be skipped where they
are written. Recommended: embedded Postgres.

## Stack

- [x] **`spec-0078-contracts`** — `Postgres` and the five contracts on it. Done
      when: every contract passes on Postgres, and the module's runtime
      classpath is unchanged.
      ([#203](https://github.com/matthewjones372/lark/pull/203))
- [ ] **`spec-0078-gaps`** — the feed's gap tests on Postgres. Done when: an
      append open on one connection holds the feed back on another until it
      commits, and a rolled-back one is passed after `gapTimeout`.

## Acceptance

```bash
./gradlew :lark-actor-journal-jdbc:build
```

## Open questions

- **Embedded Postgres or Testcontainers?** Recommended: embedded, as above.
- **Keep the H2 runs as well?** Recommended: yes; H2 is what a service's own
  tests are likeliest to use, and it runs in milliseconds.
- **Which Postgres version?** Recommended: the embedded binaries' default,
  currently 17, with the version pinned in the build so an upgrade is a
  one-line change someone chose.

Decided (2026-09-27): every open question goes as recommended. Embedded
Postgres rather than Testcontainers; the H2 runs stay; and the Postgres version
is the embedded binaries' default, pinned in the build.

Decided while building `spec-0078-contracts`: embedded-postgres 2.1.0 with the
binaries' BOM pinned at 17.5.0 and the 42.7.7 driver, all test scope. It runs
where this was built, as root and with no Docker. Every contract passed on
Postgres unchanged, and a wrong duplicate-key SQL state fails the Postgres
journal's two conflict tests, so they exercise Postgres's own errors.
