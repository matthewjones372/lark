# 0240 — Flags per tenant from Postgres

## Problem

Spec 0184 keeps flags in the service's HOCON, the same for every tenant, and a change is a redeploy. A multi-tenant
service needs more: pause checkout for one venue tonight, turn a feature on for the three tenants piloting it, and
do either from an operator's screen without a deploy. A service can keep these in a
`feature_flags` table, one row per flag for every tenant and one per tenant that differs, read under row-level
security, and has to write its own OpenFeature provider over it (its spec 0023: HOCON defaults, then the default
row, then the tenant's row, cached 30 seconds).

## Not doing

- No rule language: a row is a value for a tenant. Percentages and targeting stay flagd's (0184).
- No operator screen: `set` and `clear` are calls the service's own admin endpoint makes.
- No object flags: a value is a boolean, string, integer or double; there is no JSON library to read objects with.

## Shape

```kotlin
val CheckoutPaused = Flag.boolean("checkout.paused", default = false)     // 0181's declared flag
val flags: Module = openFeature {
    PostgresFlags(app, fallback = InMemoryProvider(defaults), ttl = 30.seconds, stale = 10.minutes)
}
provider.listening(notices)  // a Run: another process's change, seen before the ttl runs out

withTenant(TenantId("acme")) {
    CheckoutPaused.value()          // acme's row, else the default row, else the fallback, else false
}

provider.set(connection, "checkout.paused", tenant = TenantId("acme"), value = "true", reason = "fire alarm",
             by = "ops:sam")                                   // the provider's own cache drops acme at once
provider.clear(connection, "checkout.paused", tenant = TenantId("acme"))   // back to the default row
```

- **One table**, `surefold_feature_flags (key, tenant_id null, value text, reason, updated_by, updated_at)`, with
  `tenant_id` null for the row every tenant gets. The changelog's policy lets a tenant read its rows and the null ones.
- **Whose rows**: the tenant is the evaluation context's `tenant` attribute if set, else `currentTenant()`. The read
  runs in that tenant's `TenantTransactions.transaction` on `app`, the application's `DataSource`, so the policy, not
  the provider, decides what it sees. With no tenant, it runs in a transaction with `app.tenant_id` cleared, which a
  `TenantTransactions` cannot give, so the provider takes the `DataSource`.
- **Precedence**: the tenant's row, then the null row, then `fallback`, then the caller's default. The resolution's
  reason says which: `TARGETING_MATCH` for a tenant row, `STATIC` for the null row, the fallback's own otherwise.
- **Types**: a row's text is read as the type asked for; one that does not parse answers the default with
  `PARSE_ERROR`, which 0180's hook logs and counts.
- **Cache**: a 0246 `Cache`, keyed by scope, a tenant or none. Its lookup is one query that loads a tenant's rows and
  the null rows together; `ttl` is the cache's. Not `TenantCache`: that keys by the bound tenant alone, and the
  context's attribute comes first here. One lookup per scope however many evaluate at once, from 0246's
  `SingleFlight`.
- **Stale**: while Postgres cannot be read, the cache's `stale` answers the last rows read, and the provider reports
  `STALE`. Past `stale`, or with nothing cached, the flag answers the fallback and the provider reports `ERROR`.
- **Invalidation**: a trigger on the table sends `NOTIFY surefold_feature_flags` with the row's tenant, or an empty
  payload for a null row, so any writer is heard, not only `set` and `clear`. `listening`, over 0238's notices,
  drops that tenant's scope for a tenant row, and every scope for a null row or a `Gap`. Not through
  `surefold-cache-postgres`, whose payload names one key: a null row's change is every scope's.
  `set` and `clear` also drop it in their own process at once.
- **With no tenant** (a health check, a job with none), only null rows and the fallback answer.

## Why this shape

A table read under the tenant's own policy keeps flags on the same guarantee as every other row, and makes the
provider a few lines over `TenantTransactions` and 0246's `Cache`, which already has the ttl, the stale value, one
lookup per key and invalidation from `NOTIFY`. **Alternative:** flagd's schema stored as JSON per tenant, so a
tenant's row could target users; recommended against for now, since it brings flagd and its gRPC into every service
that only needs on and off per tenant. The module is **`surefold-openfeature-postgres`** in `data/`, on
`surefold-openfeature`, `surefold-tenancy`, `surefold-cache` and `surefold-postgres-notify`.

## Stack

- [x] **`spec-0240-provider`** ([#905](https://github.com/matthewjones372/surefold/pull/905)) — the module, `PostgresFlags`, its changelog, precedence, types, the dependency test.
      Done when: on Postgres, a flag answers acme's row for acme, the null row for globex, the fallback when neither
      exists, and a row of another tenant is never read.
- [x] **`spec-0240-cache`** ([#913](https://github.com/matthewjones372/surefold/pull/913)) — the provider on `Cache`, `set`, `clear`, `stale` and `STALE`.
      Done when: on a `TestClock`, a row changed by another process is seen after `ttl` and not before, a row `set`
      in this process is seen at once, and a provider whose database is down reports `STALE` and serves the cached
      values until `stale` runs out.
- [ ] **`spec-0240-notify`** — the trigger, and `listening`.
      Done when: on Postgres, a tenant row changed by plain SQL from another connection is seen before `ttl` by
      that tenant only, a null row's change is seen by every tenant, and a `Gap` drops every scope.
- [ ] **`spec-0240-docs`** — `docs/flags.md`'s section on flags per tenant, with the policy.
      Done when: the guide's example is held to a compiled source.

## Acceptance

```bash
./gradlew :surefold-openfeature-postgres:build
```

## Open questions

1. **Context attribute or `currentTenant()`?** Recommended: both, the attribute first, so a test or an operator
   tool can evaluate as another tenant without binding it.
2. **Evict across processes when a row changes?** Chosen: by `NOTIFY` (0238), from a trigger on the table, so a row
   changed by hand is heard too.
3. **Check declared flags at start, as 0195 does for flagd?** Recommended: check the null rows' values parse as the
   declared types; tenant rows can change at any time, so they are checked when read.
4. **HOCON defaults as the fallback**? `flagdFlags` installs its provider rather than answering
   one. Recommended: a `flagdProvider(config, path)` beside it in `surefold-app-openfeature-flagd`, in a spec of
   its own.
5. **Keep a history of changes?** Recommended: no table of our own; `reason` and `updated_by` are on the row, and a
   service that needs a history audits its admin endpoint.
