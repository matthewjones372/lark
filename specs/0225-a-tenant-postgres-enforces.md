# 0225 — A tenant Postgres enforces

## Problem

A multi-tenant service keeps every tenant's rows in shared tables and filters by `tenant_id` in each query. One
query that forgets the filter shows one venue's bookings to another venue. Postgres row-level security can enforce
the filter in the database, but only if every transaction says whose it is, and nothing today makes that happen, or
refuses a transaction that does not. Work that must cross tenants, such as the jobs worker, then has to switch
policies off ad hoc, with no record of who did.

## Not doing

- No tenant resolution from a request: Pelican's `TenantResolver` says whose a request is. The adapter that binds its
  answer here waits for that interface to be released.
- No schema per tenant or database per tenant: shared tables with policies only.
- No ORM integration: a `Connection` in a transaction is what the service's own code gets.

## Shape

```kotlin
withTenant(TenantId("acme")) {
    parMap(bookings) { … }                      // forks see the tenant
    Jobs.enqueue(connection, SendReminder, …)   // the tenant rides the job, as a trace does
}

val db = TenantTransactions(appSource, systemSource)
db.transaction { connection -> connection.query("select * from bookings") }   // Either<TenancyError, A>
db.system(actor = "jobs-worker", reason = "claim jobs for every tenant") { connection -> … }
```

- **`TenantId`** is a value class; **`tenant`** is a `SurefoldLocal<TenantId?>`, so forks and the stream backends that
  run on them inherit it. **`withTenant(id) { }`** binds it; **`currentTenant()`** reads it.
- **`TenantCarrier`** is a core `Carrier`, registered through `META-INF/services`, so the tenant crosses a `tell` and
  rides an enqueued job beside its trace.
- **`transaction`** opens a transaction on the application's `DataSource`, runs `select set_config('app.tenant_id',
  ?, true)`, which lasts until the transaction ends as `SET LOCAL` does, runs the block, and commits. With no tenant
  bound it answers `TenancyError.NoTenant` and touches no connection.
- **`system`** runs on a second `DataSource` whose role has `BYPASSRLS`, and writes a row to
  `surefold_tenancy_audit` (who, why, when) in the same transaction. Each call is a log line too.
- **Policies** are the service's, in its changelog. The guide gives the one to copy:
  `using (tenant_id = current_setting('app.tenant_id', true))`, with `force row level security`.
- **`surefold-tenancy-test`** seeds two tenants and checks a query run as one never sees the other's rows.
- **`surefold-jobs-tenancy`** binds each job's `tenant_id` around its run, as a `JobRunWatcher`.

## Why this shape

The system path is a second role rather than a flag in the same session. **Alternative:** policies that also pass
when `app.system = 'on'`; recommended against, since any SQL the application runs could set it. A role the
application's connections never hold cannot be switched on from them.

## Stack

- [ ] **`spec-0225-context`** — the module, `TenantId`, the local, `withTenant`, `TenantCarrier`.
      Done when: a tenant bound before a `parMap` is read in every branch, and crosses `Carriers.capture`.
- [ ] **`spec-0225-transactions`** — `TenantTransactions`, the audit changelog, `NoTenant`.
      Done when: on Postgres with a forced policy, a transaction as tenant A reads none of tenant B's rows, one with
      no tenant is refused, and a system transaction reads both and leaves an audit row.
- [ ] **`spec-0225-testkit`** — `surefold-tenancy-test`, and `docs/tenancy.md` with the policy and changelog.
      Done when: the kit fails a table whose policy was left out, and passes one with it.
- [ ] **`spec-0225-jobs`** — `surefold-jobs-tenancy`.
      Done when: a job enqueued with tenant B runs its handler with `currentTenant()` B.

## Acceptance

```bash
./gradlew :surefold-tenancy:build :surefold-tenancy-test:build :surefold-jobs-tenancy:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to keep going overnight; revisit in review).

1. **A tenant id's type:** Recommended: a string, as the jobs table stores it; a service with UUIDs passes their text.
2. **Which setting name?** Recommended: `app.tenant_id`, as the brief says, as a constant the guide's policy names.
3. **Audit in a table or only in logs?** Recommended: both. The table is what an auditor reads; the log line is what an
   operator sees as it happens.
