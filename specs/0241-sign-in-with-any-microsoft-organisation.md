# 0241 — Sign-in with any Microsoft organisation

## Problem

Spec 0228's `NimbusOidc` signs in with one provider at one issuer: `discover` requires the discovery document to
name the URL it was fetched from, and `finish` compares the ID token's `iss` with that one string. Microsoft Entra ID
for "any organisation" works differently. Its `organizations` document names the issuer as
`https://login.microsoftonline.com/{tenantid}/v2.0`, a template, so `discover` refuses it, and each token's `iss`
names the signing-in organisation's own tenant. Entra also says an email is verified with `xms_edov`, not
`email_verified`, so `OidcIdentity.email` is always null for it, and its stable user is `tid` plus `oid`, neither of
which the identity carries. Doorlist, a venue booking service on 0.14.0, offers Google and Microsoft side by side,
and builds a verifier per token tenant from the unverified `tid`, over `pelican-oidc`, to get round the same check.

## Not doing

- No personal Microsoft accounts by default: their tenant, `9188040d-…`, is refused unless allowed.
- No routes: the start and callback endpoints, and which provider a button names, are Pelican's and the service's.
- No other multi-tenant providers: Entra is the one in use; the template rule is written so another fits later.
- No change for single-issuer providers: Google and the rest verify as today.

## Shape

```kotlin
val microsoft = NimbusOidc.discoverOrganisations(clientId, Secret(clientSecret),
    tenants = AnyOrganisation)                      // or Only(setOf(tenantGuid)): a single customer's directory
val google = NimbusOidc.discover(URI("https://accounts.google.com"), clientId, Secret(googleSecret))

val providers = OidcProviders("google" to google, "microsoft" to microsoft)
val start = providers.start("microsoft", redirect)  // the OidcStart names its provider
val identity = providers.finish(start, code, state) // Either<OidcError, OidcIdentity>, by the provider start named
identity.map { it.subject to it.organisation }      // "tid:oid", and the tid
```

- **The issuer template**: `discoverOrganisations` reads `…/organizations/v2.0/.well-known/openid-configuration`
  and accepts its `{tenantid}` issuer. `finish` verifies the signature first, against the shared keys, then reads
  `tid` from the verified claims, checks it is a GUID and allowed, and checks `iss` equals the template with that
  `tid`. A mismatch is `OidcError.WrongIssuer`; a refused tenant is `OidcError.WrongOrganisation(tid)`.
- **The identity** gains `organisation` (the `tid`, null for single-issuer providers) and `name`. For Entra the
  subject is `tid:oid`, stable across apps, and the email counts when `xms_edov` is true.
- **`OidcProviders`** is a map by name: `start` writes the name into `OidcStart.provider`, and `finish` hands the
  callback to that provider, so a callback is never checked by another provider's rules.
- **Scopes and prompt**: `openid email profile`, and `prompt=select_account`, so a person signed in to two
  directories chooses one.

## Why this shape

The `tid` is read only after the signature verifies, which Doorlist's pre-verification parse cannot promise: a token
cannot choose which issuer it is checked against. **Alternative:** a `NimbusOidc` per allowed tenant, each with its
own discovery; it works for an allow-list and cannot serve "any organisation", which is the case that needs this.

## Stack

- [ ] **`spec-0241-organisations`** — `discoverOrganisations`, the template check, `WrongOrganisation`, the
      identity's `organisation`, `name` and `xms_edov`.
      Done when: against a stubbed Entra with its own keys, a token from an allowed tenant answers `tid:oid`; a
      token whose `iss` names another tenant than its `tid`, a personal-account token and a tenant outside `Only`
      each answer their own error.
- [ ] **`spec-0241-providers`** — `OidcProviders`, `OidcStart.provider`, and `docs/auth.md`'s section.
      Done when: a Google start finished with a Microsoft-shaped callback is refused, and the guide's example is
      held to a compiled source.

## Acceptance

```bash
./gradlew :surefold-auth:build :surefold-auth-oidc:build
```

## Open questions

1. **`OidcStart` gains a field**, a change to a 0.x data class. Recommended: accept it, with `provider` defaulting
   to empty for a lone `NimbusOidc`.
2. **Entra's subject: `sub` or `tid:oid`?** `sub` is pairwise per app, so it changes if the app is re-registered.
   Recommended: `tid:oid`, as Doorlist uses, documented as Entra's.
3. **Trust `xms_edov`?** It is an optional claim the app registration must request. Recommended: yes when present
   and true; absent means unverified, so the email is null.
4. **Tenants with their own signing keys** (an app with a custom key): recommended later; refused as `Signature`
   until a service needs it.
