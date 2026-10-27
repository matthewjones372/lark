# 0228 — Second factors and sign-in from elsewhere

## Problem

Venue staff can refund a booking, so a stolen password must not be enough: they need a second factor. And many
customers would rather sign in with Google than make a password. Each needs crypto that is easy to get subtly wrong:
a TOTP window that accepts a code twice, an ID token whose signature, issuer, audience, expiry or nonce went unchecked.

## Not doing

- No WebAuthn or SMS: TOTP only, as the brief asks.
- No HTTP: the redirect to the provider and the callback endpoint are Pelican's; this builds the URL and checks what
  comes back.
- No provider beyond OpenID Connect: OAuth-only providers that issue no ID token are out.

## Shape

```kotlin
val totp = PostgresTotp(dataSource, key = AesKey(secretFromConfig))
val enrolment = totp.enrol(UserId("staff-1"), issuer = "Bookings")   // a secret and an otpauth:// URI for a QR code
totp.confirm(UserId("staff-1"), code = "123456")                     // the first code switches it on
totp.verify(UserId("staff-1"), code = "654321")                      // true once per code, within one step either side

// surefold-auth: the flow, as an interface. surefold-auth-oidc: on Nimbus's OpenID Connect SDK.
val google: OidcSignIn = NimbusOidc.discover(URI("https://accounts.google.com"), clientId, Secret(clientSecret))
val start = google.start(redirect = URI("https://bookings.example/auth/callback"))   // URL, and state and nonce to keep
val identity: Either<OidcError, OidcIdentity> = google.finish(start, code, returnedState)
```

- **TOTP** is RFC 6238: HMAC-SHA1, 30-second steps, six digits, a window of one step either side. A code's step is
  stored as used, so the same code, or an earlier one, is refused. Secrets are encrypted at rest with AES-GCM under a
  key from configuration. Codes are compared in constant time.
- **OIDC** checks the state, swaps the code with PKCE, and verifies the ID token's signature against the provider's
  keys, its issuer, audience, expiry and nonce. `OidcIdentity` is the subject, the issuer, and the email if the
  provider says it is verified. `OidcError` says which check failed.

## Why this shape

The flow is an interface in `surefold-auth` and Nimbus in `surefold-auth-oidc`, so a service that does not sign in
with another provider does not carry a JSON and JOSE library. **Alternative:** reuse `pelican-oidc`, which verifies
tokens for Pelican's own `authenticated`; recommended against here, since the brief keeps Surefold off Pelican.

## Stack

- [ ] **`spec-0228-totp`** — `PostgresTotp`, enrolment, confirm, verify, encrypted secrets.
      Done when: RFC 6238's test vectors pass, and a code verifies once and not again.
- [ ] **`spec-0228-oidc`** — `OidcSignIn` in `surefold-auth`, `surefold-auth-oidc` on Nimbus.
      Done when: against a provider stubbed in the test with its own keys, a good callback answers the identity, and a
      wrong state, nonce, audience, issuer, expiry or signature each answer their own `OidcError`.
- [ ] **`spec-0228-docs`** — `docs/auth.md`, the module tables.
      Done when: the guide's example is held to a compiled source.

## Acceptance

```bash
./gradlew :surefold-auth:build :surefold-auth-oidc:build
```

## Open questions

Taken as recommended (agent, 2026-10-27, on the maintainer's instruction to keep going overnight; revisit in review).

1. **Encrypt TOTP secrets?** Recommended: yes, with a key from configuration; a database dump alone then gives no
   second factors.
2. **Recovery codes?** Recommended: not in this spec; a staff member who loses a device is re-enrolled by an admin.
