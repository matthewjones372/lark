# 0236 — Sealed values and blind indexes

## Problem

Spec 0235 gives each tenant its own data keys. Using them well is where services go wrong: a nonce reused, a ciphertext
that can be copied from one row or tenant to another and still decrypts, an old key version nobody can read once
rotation starts, or an email column that can no longer be looked up once it is encrypted. A service that encrypts
personal data needs every personal field sealed under the tenant's `enc` key, bound to its place, and found by equality
through an HMAC under a separate `bidx` key, during rotation as well as after. `surefold-auth`'s `AesKey` seals TOTP
secrets under one key from configuration, with no versions and no index.

## Not doing

- No search beyond equality: prefix, trigram and name search leak through frequency, and are the service's.
- No normalising: the caller passes the value it wants matched (an email lower-cased, a phone as E.164).
- No column types or ORM mapping: a sealed value is a `ByteArray` for a `bytea` column; mapping rows is the service's.
- No purpose audit of reads ("who looked at Jo's details"): the service writes it, around `open`.

## Shape

```kotlin
val cipher = TenantCipher(keys)                                 // spec 0235's TenantKeys
val place = Place(table = "customers", column = "email", row = customerId.toString())

val sealed: Either<SealError, ByteArray> = cipher.seal(email.toByteArray(), place)   // as the bound tenant
val opened: Either<SealError, ByteArray> = cipher.open(sealed, place)
cipher.reseal(sealed, place)              // under the current version, or null when it already is: re-encryption

val index = BlindIndex(keys)
index.of("jo@example.com", Field("customers", "email"))         // 32 bytes, under the current `bidx` version
index.candidates("jo@example.com", Field("customers", "email"))  // one per live version: `where email_bidx = any(?)`
```

- **The envelope** is one `bytea`: `0x01` (format) · key version (4 bytes) · nonce (12) · ciphertext · tag (16).
  AES-256-GCM from the JDK, a fresh random nonce each time, a 128-bit tag.
- **Associated data** is `surefold/v1|<tenant>|<table>|<column>|<row>`, so a ciphertext moved to another row, column,
  table or tenant fails authentication. That failure is `SealError.Tampered`, logged and counted, never an empty value.
- **Versions**: `seal` uses the current `enc` version; `open` reads the version from the envelope and asks spec
  0235's keys for it; a retired one is `SealError.UnknownVersion`.
- **Padding**: the plaintext is padded to a multiple of 16 bytes, its length inside, so a stored length says a
  bucket, not how long a name is.
- **Blind indexes** are `HMAC-SHA-256(bidx key, field || 0x00 || value)`, full length. The field is in the input,
  so two columns holding the same email do not share an index value. `candidates` answers one per version still
  stored, so a lookup finds rows written before and after a rotation.
- **A count of encryptions** per tenant and version, as a metric; spec 0235's rotation keeps each far below GCM's
  2^32 random-nonce limit, and a version past 2^30 logs an error.

## Why this shape

The place is passed, not inferred, because only the caller knows the row's key; and binding it is the cheap guard
against swapping two customers' emails with nothing but database write. A separate HMAC key, not deterministic
encryption: **alternative:** AES-SIV on searchable fields, one column instead of two; recommended against, since it
leaks equality the same way and makes the encryption key also the lookup key.

## Stack

- [x] **`spec-0236-seal`** ([#863](https://github.com/matthewjones372/surefold/pull/863)) — `TenantCipher`: seal, open, the envelope, associated data, padding, `SealError`.
      Done when: NIST's GCM vectors pass, a value opens in its place, and fails as `Tampered` after its bytes are
      copied to another row, column or tenant.
- [ ] **`spec-0236-versions`** — opening by version, `reseal`, the encryption count.
      Done when: after a rotation, a value sealed at version 1 opens, reseals to version 2, and `UnknownVersion`
      once version 1 is retired.
- [ ] **`spec-0236-index`** — `BlindIndex`: `of`, `candidates`, and the guide's section on lookups and what they leak.
      Done when: on Postgres, mid-rotation, `any(candidates)` finds a row indexed under each version, and tenant A's
      index of an email differs from B's.

## Acceptance

```bash
./gradlew :surefold-tenancy-crypto:build
```

## Open questions

1. **Pad by default?** Padding costs up to 15 bytes a value. Recommended: on by default, `pad = false` for values of
   fixed length such as secrets.
2. **Move `PostgresTotp` onto this?** Recommended: not here; its single `AesKey` stays, and a later spec lets it take
   a `TenantCipher` when a service wants TOTP secrets shreddable.
3. **A typed wrapper for opened values** (whose `toString` is masked)? Recommended: no;
   `surefold-auth`'s `Secret` already masks, and a service that wants a type of its own wraps the bytes.
