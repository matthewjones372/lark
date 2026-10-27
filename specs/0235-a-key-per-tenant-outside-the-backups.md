# 0235 — A key per tenant, outside the backups

## Problem

Row-level security (spec 0225) keeps one tenant's rows from another tenant's queries, and does nothing for anyone who
reads the database another way: a snapshot shared by mistake, a restored side database, an operator's `psql`. Nor can
a closed tenant's personal data be erased from twelve months of backups. Encrypting each tenant's fields under keys
that tenant alone owns, rooted in a KMS key that lives in no backup, answers both: delete the root key and every copy
is unreadable. Doorlist, a venue booking service on 0.14.0, decided exactly this (its ADR 0013) and is building the
key half itself: a provider port, a `tenant_keys` table, a cache with soft and hard limits, rotation and shredding.

## Not doing

- No encryption of values: sealing, associated data and blind indexes are spec 0236, on the keys this one holds.
- No re-encryption run: a chunked job that rewrites old versions is the service's, on `surefold-jobs`.
- No KMS but AWS's: the port is two calls wide, so another is an adapter of its own later.
- No key policies or IAM: which role may unwrap whose key is the service's infrastructure, named in the guide.

## Shape

```kotlin
val kms: Kms = AwsKms(KmsClient.create(), aliasPrefix = "alias/bookings/prod/tenant/")   // or InMemoryKms()
val keys = TenantKeys(tenants, kms, KeyCache(soft = 15.minutes, hard = 60.minutes, tenants = 1_000))

keys.provision(TenantId("acme"))                 // a root key, and version 1 of `enc` and `bidx`; idempotent
keys.current(KeyPurpose.Enc)                     // Either<KeyError, DataKey>, for the bound tenant
keys.version(KeyPurpose.Bidx, 3)                 // an older version, to open or look up what it wrote
keys.rotate(TenantId("acme"))                    // version n+1 of each; older ones stay until retired
keys.retire(TenantId("acme"), KeyPurpose.Enc, 1) // once no row refers to it
keys.shred(TenantId("acme"), waiting = 30.days)  // deletes its rows, schedules the root key's deletion
```

- **The port**, `Kms`: `createRoot(tenant)`, `generateDataKey(root, context)`, `decrypt(root, wrapped, context)`,
  `scheduleDeletion(root, waiting)`. The context is `{tenant_id, purpose, version}`, so a key policy can refuse an
  unwrap under another tenant's context. Each answers `Either<KeyError, _>`: `Unavailable`, `Denied`, `Invalid`.
- **`AwsKms`** in `surefold-tenancy-crypto-aws`: one symmetric customer managed key per tenant, found by alias, created
  on first `provision`. **`InMemoryKms`** in the main module: keys in a map, deletion that makes `decrypt` answer
  `Denied`, and a count of calls, for tests and local runs.
- **`surefold_tenant_keys`**: `(tenant_id, purpose, version, wrapped, root, state, created_at)`, one `active` version
  per purpose, the rest `decrypt_only`. Read in the tenant's own `TenantTransactions.transaction`, under the guide's
  policy; key bytes are never stored unwrapped.
- **The cache** keeps a tenant's unwrapped versions in process memory: refreshed from KMS after `soft`, still used up
  to `hard` while KMS is down, never after; least recently used tenant out past `tenants`; bytes zeroed on eviction.
  `evict(tenant)` drops one at once.
- **Shredding** deletes the tenant's key rows and schedules its root key's deletion. Once KMS deletes it, every
  wrapped key in every backup is unreadable.

## Why this shape

A KMS key per tenant, not one shared key with per-tenant data keys: a shared key's wrapped data keys sit in every
backup beside the one key that unwraps them, so deleting a tenant's rows shreds nothing in the backups. It costs a
KMS key a tenant. **Alternative:** a shared root, cheaper at thousands of tenants; offered as an `AwsKms` setting,
not the default. The module is **`surefold-tenancy-crypto`**, not `surefold-crypto`, because every key belongs to a
`TenantId`, is read in that tenant's transaction, and is found through `currentTenant()`, all from `surefold-tenancy`.

## Stack

- [ ] **`spec-0235-port`** — `surefold-tenancy-crypto`: `Kms`, `KeyError`, `InMemoryKms`, the dependency test.
      Done when: a key wrapped under tenant A's context does not unwrap under B's, and a deleted root answers `Denied`.
- [ ] **`spec-0235-keys`** — `TenantKeys`, its changelog, provision, current, version, rotate, retire.
      Done when: on Postgres, rotation makes version 2 current while version 1 still answers, and tenant A's
      transaction reads none of B's key rows.
- [ ] **`spec-0235-cache`** — `KeyCache`: soft, hard, size, evict.
      Done when: on a `TestClock` with `InMemoryKms` failing, a key is served at 59 minutes and refused at 61, and
      KMS is called once per tenant per soft period however many threads ask.
- [ ] **`spec-0235-aws`** — `surefold-tenancy-crypto-aws`: `AwsKms`, against LocalStack's KMS.
      Done when: provision, unwrap, a context mismatch refused, and a scheduled deletion all hold on LocalStack.
- [ ] **`spec-0235-shred`** — `shred`, and `docs/tenancy-crypto.md` with the table's policy and a key policy to copy.
      Done when: after `shred`, the tenant's rows from a copy of the database taken before it cannot be unwrapped.

## Acceptance

```bash
./gradlew :surefold-tenancy-crypto:build :surefold-tenancy-crypto-aws:build
```

## Open questions

1. **A platform key beside the tenants'?** Doorlist encrypts its own staff and contacts under a platform root.
   Recommended: a `KeyOwner` that is a tenant or `Platform`, so one cache and one table serve both.
2. **Evict across processes on rotation or shredding?** Recommended: `TenantKeys` sends `NOTIFY surefold_tenant_keys`
   and each process evicts on it, once spec 0238's listener ships; until then, at most `soft` late.
3. **Who may provision and rotate?** In Doorlist only the worker may `GenerateDataKey`. Recommended: the guide says so
   and gives the key policy; the API does not split it.
4. **Multi-Region replicas** for a disaster-recovery copy: recommended later, as an `AwsKms` setting, once the
   deletion order of a primary and its replicas is verified on a real account.
