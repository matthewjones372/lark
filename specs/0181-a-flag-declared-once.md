# 0181 — A flag declared once

## Problem

After 0180, a flag is read as `client.getBooleanValue("adoption.registry-check",
true)`. The key and the default are repeated at every call site, so two call
sites can disagree on the default, a renamed key compiles, and nothing lists
which flags a service reads. The caller also has to hold a `Client`, which
means threading one through code that otherwise only needs the value.

## Not doing

- **No typed errors.** A flag that cannot be evaluated answers with its
  default; 0180's hook reports why. A flag never fails a request.
- **No types beyond the SDK's four scalars.** Durations, instants, enums and
  object flags are 0183.
- **No registry or listing endpoint.** Declarations are `val`s.
- **No change-listener API.** A provider's change events stay the SDK's.

## Shape

In `surefold-openfeature`. Factories on `Flag`'s companion, as `Schedule`'s are:

```kotlin
object AdoptionFlags {
    val registryCheck = Flag.boolean("adoption.registry-check", default = true)
    val perAdopterLimit = Flag.int("adoption.per-customer-limit", default = 3)
}

evaluatedFor(customer.value) {
    if (AdoptionFlags.registryCheck.value()) registry.verify(chip)
}
val detail: FlagEvaluationDetails<Int> = AdoptionFlags.perAdopterLimit.details()
```

- `Flag<A>`, a value with a `key` and a `default`; `Flag.boolean`,
  `Flag.string`, `Flag.int`, `Flag.double` construct one.
- `value()` and `details()` evaluate against `featureClient`, a
  `SurefoldLocal<Client>` defaulting to `OpenFeatureAPI.getInstance().client`,
  and the context 0180 binds.
- `featureClient.locally(otherClient) { ... }` swaps the client, which is
  what a test with its own provider domain does.

## Why this shape

A flag as a declared value puts key, default and type in one place. Named
factories over one `Flag(key, default)` inferring from the default:
`default = 3` versus `3.0` would choose int or double silently. Reading the
client from a `SurefoldLocal` matches `logger` and `metrics`: a call site needs
nothing passed in; an injected `Flags` class over a `Client` was the
alternative and puts a dependency on every class that reads one flag.
Keeping `Flag` over `FeatureFlag`: the SDK's in-memory `Flag` is rarely
imported once flags live in config (0182); an alias covers the file that
needs both.

## Stack

- [x] **`spec-0181-flags`** ([#543](https://github.com/matthewjones372/lark/pull/543)) — `Flag`, the four factories, `value()`,
      `details()`, `featureClient`.
      Done when: a missing flag answers its default, a wrong-typed one
      answers its default with `TYPE_MISMATCH`, and a fork reads the client
      its opener bound.
- [x] **`spec-0181-docs`** ([#545](https://github.com/matthewjones372/lark/pull/545)) — `docs/flags.md` with compiled fences.
      Done when: the guide's test compiles them.

## Acceptance

```bash
./gradlew :surefold-openfeature:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. New `docs/flags.md`, or a section in `docs/observability.md`?
   **New page**: flags are configuration, not observation. **Answered:** as recommended.
2. Should `Flag` carry a description for a future listing? **No**; add it
   when something reads it. **Answered:** as recommended.
