# 0240 — Flags per tenant from Postgres

## Problem

Spec 0184 keeps flags in the service's HOCON, the same for every tenant, and a change is a redeploy. A multi-tenant
service needs more: pause checkout for one venue tonight, turn a feature on for the three tenants piloting it, and
do either from an operator's screen without a deploy. Doorlist, a venue booking service on 0.14.0, keeps these in a
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
val flags: Module = openFeature { PostgresFlags(tenants, fallback = InMemoryProvider(defaults), ttl = 30.seconds) }

withTenant(TenantId("acme")) {
    CheckoutPaused.value()          // acme's row, else the default row, else the fallback, else false
}

PostgresFlags.set(connection, "checkout.paused", tenant = TenantId("acme"), value = "true", reason = "fire alarm",
                  by = "ops:sam")
PostgresFlags.clear(connection, "checkout.paused", tenant = TenantId("acme"))   // back to the default row
```

- **One table**, `surefold_feature_flags (key, tenant_id null, value text, reason, updated_by, updated_at)`, with
  `tenant_id` null for the row every tenant gets. The guide's policy lets a tenant read its rows and the null ones.
- **Whose rows**: the tenant is the evaluation context's `tenant` attribute if set, else `currentTenant()`. The read
  runs in that tenant's `TenantTransactions.transaction`, so the policy, not the provider, decides what it sees.
- **Precedence**: the tenant's row, then the null row, then `fallback`, then the caller's default. The resolution's
  reason says which: `TARGETING_MATCH` for a tenant row, `STATIC` for the null row, the fallback's own otherwise.
- **Types**: a row's text is read as the type asked for; one that does not parse answers the default with
  `PARSE_ERROR`, which 0180's hook logs and counts.
- **Cache**: one query loads a tenant's rows and the null rows together, kept for `ttl`; `set` and `clear` evict
  in this process. The provider is `STALE` while Postgres cannot be read and a cached copy is being served.
- **With no tenant** (a health check, a job with none), only null rows and the fallback answer.

## Why this shape

A table read under the tenant's own policy keeps flags on the same guarantee as every other row, and makes the
provider a few lines over `TenantTransactions`. **Alternative:** flagd's schema stored as JSON per tenant, so a
tenant's row could target users; recommended against for now, since it brings flagd and its gRPC into every service
that only needs on and off per tenant. The module is **`surefold-openfeature-postgres`** in `data/`, on
`surefold-openfeature` and `surefold-tenancy`.

## Stack

- [ ] **`spec-0240-provider`** — the module, `PostgresFlags`, its changelog, precedence, types, the dependency test.
      Done when: on Postgres, a flag answers acme's row for acme, the null row for globex, the fallback when neither
      exists, and a row of another tenant is never read.
- [ ] **`spec-0240-cache`** — the ttl cache, `set`, `clear`, `STALE`.
      Done when: on a `TestClock`, a row changed by another process is seen after `ttl` and not before, and a
      provider whose database is down reports `STALE` and serves the cached values.
- [ ] **`spec-0240-docs`** — `docs/flags.md`'s section on flags per tenant, with the policy.
      Done when: the guide's example is held to a compiled source.

## Acceptance

```bash
./gradlew :surefold-openfeature-postgres:build
```

## Open questions

1. **Context attribute or `currentTenant()`?** Recommended: both, the attribute first, so a test or an operator
   tool can evaluate as another tenant without binding it.
2. **Evict across processes when a row changes?** Recommended: by `NOTIFY` once spec 0238 ships; until then `ttl`.
3. **Check declared flags at start, as 0195 does for flagd?** Recommended: check the null rows' values parse as the
   declared types; tenant rows can change at any time, so they are checked when read.
4. **HOCON defaults as the fallback**, as Doorlist wants? `flagdFlags` installs its provider rather than answering
   one. Recommended: a `flagdProvider(config, path)` beside it in `surefold-app-openfeature-flagd`, in a spec of
   its own.
5. **Keep a history of changes?** Recommended: no table of our own; `reason` and `updated_by` are on the row, and a
   service that needs a history audits its admin endpoint.
