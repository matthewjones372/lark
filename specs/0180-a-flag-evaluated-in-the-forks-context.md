# 0180 — A flag evaluated in the fork's context

## Problem

A service using OpenFeature sets who is asking (a targeting key, a customer
tier) once, where the request comes in, and reads flags further down. The Java
SDK carries that "transaction context" through a `TransactionContextPropagator`,
and the one it ships is `ThreadLocalTransactionContextPropagator`. Every Surefold
combinator opens new threads, so a flag read inside a `parZip` branch or an
actor's handler sees an empty context and answers for nobody: a per-customer
rollout silently becomes the default for everyone.

Nothing reports an evaluation either. A flag that fell back to its default
because the provider was down looks the same as one that answered.

## Not doing

- **No typed flags.** Callers use the SDK's `Client` as it is; 0181 adds
  values over it.
- **No `surefold-app` component or provider.** 0182.
- **No tracing.** OpenTelemetry's own OpenFeature hook already exists and
  works over `surefold-otel`'s context.
- **No ServiceLoader registration.** The SDK reads its propagator from a
  setter, not a service file.

## Shape

A module, `surefold-openfeature`, on `surefold` and `dev.openfeature:sdk`.

```kotlin
OpenFeatureAPI.getInstance().useSurefold()          // propagator + hook, once, in main

fun adopt(customer: CustomerId, pet: PetId) =
    evaluatedFor(customer.value, "tier" to customer.tier) {
        parZip(
            { client.getBooleanValue("adoption.registry-check", true) }, // sees customer
            { client.getIntegerValue("adoption.per-customer-limit", 3) }, // so does this
        ) { check, limit -> ... }
    }
```

- `featureContext: SurefoldLocal<EvaluationContext>`, empty by default.
- `SurefoldTransactionContextPropagator` reads and writes `featureContext`.
- `evaluatedFor(targetingKey, vararg attributes, block)` binds a context
  merged over the one already bound, so a nested call adds rather than
  replaces.
- `SurefoldFeatureHook`: after each evaluation, `counter("openfeature.evaluations",
  "flag", "variant", "reason")`; on error, `logWarn` with the flag key and
  error code, and `counter("openfeature.errors", "flag", "code")`. Uses
  Surefold's `logger` and `metrics` locals, so whatever backend is bound gets it.
- `OpenFeatureAPI.useSurefold()` sets the propagator and adds the hook.

## Why this shape

The propagator is the seam the SDK already offers for exactly this, so the
whole SDK — including clients other libraries create — reads the forked
context without knowing about Surefold. The alternative, a wrapper `Client` that
merges the context on each call, misses every evaluation made through a client
Surefold did not hand out. Recommended: the propagator.

## Stack

- [x] **`spec-0180-context`** ([#540](https://github.com/matthewjones372/lark/pull/540)) — the module, `featureContext`, the propagator,
      `evaluatedFor`, `useSurefold()` setting the propagator, and the dependency
      test.
      Done when: a flag targeted at one key answers for that key inside a
      `parZip` branch and an actor handler, and for nobody outside the block.
- [x] **`spec-0180-hook`** ([#541](https://github.com/matthewjones372/lark/pull/541)) — `SurefoldFeatureHook`, added by `useSurefold()`.
      Done when: an evaluation against a failing provider writes one warning
      and one `openfeature.errors` reading under `TestMetrics`, and still
      returns the default.

## Acceptance

```bash
./gradlew :surefold-openfeature:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. The SDK depends on `slf4j-api` at runtime. Allow it in this module's
   `NoOtherDependenciesTest`, or exclude it? **Allow**: the SDK logs through
   it and fails without it; it is the SDK's, not ours. **Answered:** as recommended.
2. Should the hook's counter carry `variant`? Unbounded variants would
   explode cardinality. **Yes, with `reason`**; variants are a small declared
   set in every provider we know of. **Answered:** as recommended.
3. Does `useSurefold()` install globally, or per `Client`
   (`client.addHooks`)? **Globally**: the propagator is global anyway, and a
   hook on one client misses the rest. **Answered:** as recommended.
