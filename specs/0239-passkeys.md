# 0239 — Passkeys

## Problem

Spec 0228 gave venue staff TOTP as a second factor and left WebAuthn out. Passkeys are now what people expect: a
sign-in with no password to steal and no code to phish, bound to the site's origin by the browser. Verifying one is
a long list of checks (origin, RP ID, challenge, flags, algorithm, signature, sign count, backup state), and the
challenges and credentials have to be stored somewhere every instance shares. Doorlist, a venue booking service on
0.14.0, built it itself: a `WebAuthn` interface over webauthn4j-core, two tables, single-use challenges, and a
software authenticator for its tests.

## Not doing

- No attestation: `none` only. No FIDO metadata service, no allow-list of authenticator models.
- No HTTP or JavaScript: the endpoints, cookies and `navigator.credentials` calls are Pelican's and the page's.
- No sign-in policy: whether a passkey replaces the password, counts as a second factor, or steps a session up is
  the service's; this answers who proved what, and `PostgresSessions` makes the session.
- No account recovery: spec 0228's answer stands.

## Shape

```kotlin
val passkeys = PostgresPasskeys(dataSource, RelyingParty(id = "bookings.example", name = "Bookings",
                                                          origins = setOf("https://bookings.example")))

val creating = passkeys.registrationOptions(UserId("staff-1"), name = "jo@venue.example", displayName = "Jo",
                                            boundTo = sessionId)  // JSON for navigator.credentials.create
passkeys.register(UserId("staff-1"), responseJson, label = "MacBook", boundTo = sessionId)  // Either<_, Passkey>

val asking = passkeys.assertionOptions(user = null, boundTo = browserId)   // null: any passkey on this site
when (val used = passkeys.verify(responseJson, boundTo = browserId)) {    // Either<PasskeyError, PasskeyUse>
    is Either.Right -> sessions.create(used.value.user, tenant = …)
    is Either.Left -> refuse()                                              // one answer; the case is for the log
}
passkeys.list(UserId("staff-1")); passkeys.remove(UserId("staff-1"), PasskeyId(id))
```

- **Options** are the Level 3 JSON (`PublicKeyCredentialCreationOptionsJSON` and `…RequestOptionsJSON`):
  discoverable credentials, user verification required, ES256 and RS256, `excludeCredentials` of the user's own.
- **Challenges** are 32 random bytes, stored only as their hash with a purpose, the user if known, `boundTo`, and a
  five-minute expiry. `verify` and `register` read the challenge from the response's `clientDataJSON` and spend it
  in the statement that finds it, so a response verifies once.
- **Verification** is webauthn4j's, inside: origin in `origins`, RP ID hash, challenge, user present and verified,
  algorithm, signature. A sign count that does not go up is `PasskeyError.CloneSuspected`, and the credential is
  suspended. Every webauthn4j exception becomes a `PasskeyError`.
- **User handles** are 64 random bytes per user, never the user id or email.
- **Tables**: `surefold_passkeys` (credential id, COSE public key, sign count, transports, backup flags, AAGUID,
  label, created and last used) and `surefold_passkey_challenges`, in Surefold's auth changelog.
- **`SoftwareAuthenticator`** in `surefold-auth-webauthn-test`: an EC P-256 authenticator that answers `create` and
  `get` as a browser would, for a given origin, with a counter a test can wind back.

## Why this shape

webauthn4j, wrapped, as Doorlist does: Apache-2.0, maintained, Level 3 JSON parsing built in, and its own
Jackson emits the options, so it is the module's one external library. **Alternative:** Yubico's
`java-webauthn-server`, with a credential-repository interface we would implement; recommended against, since it
wants its own storage shape and adds more dependencies. Writing CBOR and COSE ourselves is not an option worth one.

## Stack

- [ ] **`spec-0239-verify`** — `surefold-auth-webauthn`: `RelyingParty`, options, registration and assertion
      verification, `PasskeyError`; the test kit's `SoftwareAuthenticator`.
      Done when: a created credential verifies, and a wrong origin, RP ID, challenge, signature, missing user
      verification and a lower sign count each answer their own error.
- [ ] **`spec-0239-store`** — `PostgresPasskeys`, its changelog, challenges, handles, list, remove.
      Done when: a challenge is spent once by 20 threads verifying at once, an expired one is refused, one bound to
      another session is refused, and a cloned credential is suspended.
- [ ] **`spec-0239-docs`** — `docs/auth.md`'s section on passkeys, the module tables.
      Done when: the guide's example is held to a compiled source.

## Acceptance

```bash
./gradlew :surefold-auth-webauthn:build :surefold-auth-webauthn-test:build
```

## Open questions

1. **Module or inside `surefold-auth`?** Recommended: its own module, as `surefold-auth-oidc` is, so a service with
   no passkeys does not carry webauthn4j and Jackson.
2. **What `boundTo` is.** Doorlist binds a sign-in challenge to the browser that asked and the others to a session.
   Recommended: an opaque string the caller chooses, required, compared in constant time.
3. **Suspend on a cloned count, or only refuse?** Synced passkeys always send 0, which is allowed. Recommended:
   suspend, since a count going back on a device-bound key means a copy exists.
4. **Credentials per tenant?** Recommended: no; a passkey belongs to a user, as a password does in spec 0227.
