# 0237 — A system path with no bypass

## Problem

Spec 0225's `system` runs on a role with `BYPASSRLS`. It is audited, but whatever runs on that role reads every
tenant's every row: one careless query on the system `DataSource`, or one injection into it, is a full leak. A stricter
model is better: its system role holds no table grants at
all and may only `EXECUTE` a pinned list of `SECURITY DEFINER` functions, each returning the fewest columns its
purpose needs, and a schema test fails the build when that list changes. It also sets the acting user and member
role in every tenant transaction, so restrictive policies can narrow by role and fail closed when it is missing.
Today a service has to hand-roll all three.

## Not doing

- No replacement: `system` and its `BYPASSRLS` role stay. This is a second mode a service chooses.
- No functions of Surefold's own for a service's tables: each `sys` function is the service's, in its changelog.
- No jobs on this path: `surefold-jobs`' tables carry no policy, so the worker uses a role with grants on them.

## Shape

```kotlin
val db = TenantTransactions(app = appSource, settings = listOf(Setting("app.member_role", absent = "none"),
                                                              Setting("app.user_id", absent = "")))
withTenant(TenantId("acme"), "app.member_role" to "manager", "app.user_id" to user.id) {
    db.transaction { connection -> … }              // sets app.tenant_id and both settings, transaction-locally
}

val sys = SystemFunctions(systemSource, schema = "sys")
sys.call(actor = "api", reason = "resolve the request's host") { calls ->
    calls.one("resolve_host", slug) { row -> TenantId(row.getString("tenant_id")) }
}

checkSystemFunctions(superuserSource, role = "booking_system", expected = setOf("sys.resolve_host", "sys.due_holds"))
```

- **Settings**: each declared `Setting` is set in every tenant transaction, to its bound value or to `absent`, so a
  restrictive policy reading `current_setting('app.member_role')` sees `none`, never a pooled connection's last value.
  Names are checked as `word.word` when declared. Values bind beside the tenant, so forks see them.
- **`SystemFunctions.call`** opens a transaction on the system role and hands the block `FunctionCalls`, which can
  only `one`, `optional` or `query` a function by name in `schema`: `select * from sys.resolve_host(?)`. No
  `Connection` is handed out. Names are checked as identifiers; arguments are bound.
- **Audit**: every `call` logs its actor and reason; `audited = true` also writes the 0225 audit row, through
  `surefold_tenancy_audit_write(actor, reason)`, a definer function in Surefold's changelog, so the role needs no grant.
- **`checkSystemFunctions`** in `surefold-tenancy-test` fails, naming the difference, when: the set of `SECURITY
  DEFINER` functions the role may execute differs from `expected`; one of them has no pinned `search_path`; the role
  holds any table, sequence or schema-create privilege; or it is a superuser or `BYPASSRLS`.
- **The guide** gives the roles: a `NOLOGIN` definer role owns the functions and holds only the grants they use,
  since a function owned by the table owner under forced security reads nothing; `revoke execute … from public`.

## Why this shape

The database is the guarantee: a role with no grants cannot read a row whatever SQL it runs, so `FunctionCalls`
hiding the `Connection` is a convenience, not the control. The cost is a migration per cross-tenant need.
**Alternative:** the system role with a policy per table that passes when it runs; recommended against, since a
policy covers whole rows and every column, where a function answers only what its purpose needs.

## Stack

- [x] **`spec-0237-settings`** ([#867](https://github.com/matthewjones372/surefold/pull/867)) — `Setting`, `withTenant` with values, set in every `transaction`.
      Done when: a restrictive policy on `app.member_role` hides a row from `none` and shows it to `manager`, and a
      pooled connection's next transaction sees `none`.
- [ ] **`spec-0237-functions`** — `SystemFunctions`, `FunctionCalls`, the audit function.
      Done when: on a role with only `EXECUTE`, a call returns the function's rows, writes an audit row, and a plain
      `select` on the same role is refused by Postgres.
- [ ] **`spec-0237-check`** — `checkSystemFunctions`, and `docs/tenancy.md`'s section on this mode.
      Done when: the check fails on an extra function, a function without `search_path`, a table grant and
      `BYPASSRLS`, each with its own message, and passes the guide's example.

## Acceptance

```bash
./gradlew :surefold-tenancy:build :surefold-tenancy-test:build
```

## Open questions

1. **Carry the settings across a `tell` and into a job, as the tenant is?** Recommended: no; a job runs later and is
   not its enqueuer acting, so it runs as the tenant with every setting `absent`.
2. **Audit every call by default?** A host lookup can run on every request. Recommended: log always, audit row
   off by default, on for writes and operator work.
3. **One class with two modes, or `SystemFunctions` beside `TenantTransactions`?** Recommended: beside, so a service
   on this mode has no `system` method to reach for.
