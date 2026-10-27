# 0227 — Sessions and passwords

## Problem

A booking service signs in customers and venue staff. Each service that does this stores sessions its own way, hashes
passwords with whatever was to hand, and compares tokens with `==`, which leaks through timing how much of a token
was right. Pelican will own cookies, CSRF and per-endpoint permission checks, through a `SessionResolver` it is adding;
something has to own the storage and the crypto underneath it.

## Not doing

- No HTTP: cookies, CSRF, redirects and sign-in pages are Pelican's. The adapter from Pelican's `SessionResolver` to
  `Sessions` waits for that interface to be released.
- No user table: a user is an id the service owns. This stores sessions, credentials and roles against it.
- No account recovery flows, email sending or lockout policy; magic links are issued here and sent by the service.
- TOTP and OpenID Connect are spec 0228.

## Shape

```kotlin
val passwords = Passwords(Argon2Settings(memoryKib = 19_456, iterations = 2, parallelism = 1))  // from config
val stored: PasswordHash = passwords.hash(Secret("correct horse"))
when (val checked = passwords.verify(Secret(typed), stored)) {
    is Checked.Valid -> checked.rehashed?.let { users.store(id, it) }   // parameters changed since: store the new hash
    Checked.Invalid -> refuse()
}

val sessions = PostgresSessions(dataSource, SessionSettings(idle = 30.minutes, absolute = 12.hours))
val (token, session) = sessions.create(UserId("u-1"), tenant = "acme")    // token goes in the cookie, once
sessions.resolve(token)        // Principal(user, tenant, roles, permissions), or null: unknown, idle, past absolute, revoked
sessions.rotate(token)         // a new token for the same session, the old one dead: after sign-in or a privilege change
sessions.revoke(token); sessions.revokeAll(UserId("u-1"))

val links = PostgresMagicLinks(dataSource, ttl = 15.minutes)
val link = links.issue(UserId("u-1"))           // a token to put in an emailed URL
links.redeem(link)                              // the user, once; null after, or once expired

roles.grant(UserId("u-1"), tenant = "acme", role = "venue-staff"); roles.allow("venue-staff", "bookings:refund")
```

- **Tokens** are 32 random bytes from `SecureRandom`, handed out once as URL-safe base64, and stored only as their
  SHA-256 hash. A lookup by hash is compared again with `MessageDigest.isEqual`. A token's `toString` is masked.
- **Sessions** end at whichever comes first: `idle` since last resolved, or `absolute` since created. Resolving moves
  the idle expiry on. Rotation and revocation are immediate across instances, since every resolve reads the table.
- **Passwords** are Argon2id from Bouncy Castle, stored as the PHC string (`$argon2id$v=19$m=…,t=…,p=…$salt$hash`), so
  a stored hash says its own parameters and a verify can tell they changed.
- **Magic links** are single use: redeeming deletes the row in the statement that reads it.
- **Roles** are granted per user and per tenant, or for every tenant; a role allows permissions; `resolve` answers
  the principal with both.

## Why this shape

Bouncy Castle is pure Java and maintained, so no native library comes with it. **Alternative:** `argon2-jvm`, which
binds the reference C implementation through JNA; faster, and a native library on every platform the service runs.
Recommended: Bouncy Castle.

## Stack

- [x] **`spec-0227-passwords`** — `surefold-auth`, `Secret`, `Passwords`, `Argon2Settings`, rehash.
      Done when: a hash made at one cost verifies, and verifying it after the settings change answers a rehash.
- [ ] **`spec-0227-sessions`** — `PostgresSessions`: create, resolve, rotate, revoke, its changelog.
      Done when: on a `TestClock`, a session dies after `idle` unresolved and after `absolute` however often resolved,
      a rotated token stops resolving, and a revoked one stops on another instance.
- [ ] **`spec-0227-links`** — `PostgresMagicLinks`.
      Done when: a link redeems once, and 20 threads redeeming one link at once get it once between them.
- [ ] **`spec-0227-roles`** — roles and permissions, loaded into the principal.
      Done when: a user with a role in one tenant has its permissions there and not in another.

## Acceptance

```bash
./gradlew :surefold-auth:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to keep going overnight; revisit in review).

1. **Default Argon2id cost:** Recommended: OWASP's 19 MiB, 2 iterations, 1 lane, set in configuration.
2. **Slide the idle expiry on every resolve?** Recommended: only when more than a minute has passed since the last,
   so a page of twenty requests is one write, not twenty.
3. **Where do roles live?** Recommended: here, in two tables, since the brief puts them in the principal that
   `SessionResolver` returns; a service with its own can supply a `RoleSource` instead.
