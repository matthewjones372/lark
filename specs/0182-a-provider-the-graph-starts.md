# 0182 — A provider the graph starts

## Problem

A service has to set its OpenFeature provider before the first request and
shut the SDK down when it stops. Today that is two lines in `main`, outside the
graph: nothing makes a handler that reads a flag wait for the provider, a
provider that never becomes ready still lets the service report ready, and a
test that builds the graph without `main` gets the SDK's no-op provider and
every default.

## Not doing

- **No provider of our own.** Any `FeatureProvider` is passed in; flags in
  HOCON are 0184.
- **No live reload.** Same rule as 0023.
- **No per-domain providers.** One provider, the default domain.

## Shape

A module, `lark-app-openfeature`, on `lark-app` and `lark-openfeature`:

```kotlin
val flags: Module = openFeature { InMemoryProvider(mapOf(...)) }   // any FeatureProvider

single { client: Client -> AdoptionService(client) }  // starts after the provider is ready
```

`openFeature` is an `install`:

- on the way in, `setProviderAndWait` (blocking is fine on a virtual
  thread) and `useLark()` from 0180; a provider whose init throws refuses
  the start;
- a `Client` node, also bound into 0181's `featureClient` for the graph's
  lifetime, so `Flag.value()` and an injected `Client` read one provider;
- a readiness check, down while the provider reports `ERROR` or `STALE`;
- on release, `OpenFeatureAPI.shutdown()`.

## Why this shape

The provider is a resource like a pool: it has a start that can fail, a
ready state and a release, which is what `install` and readiness already
model. The alternative, leaving the two lines in `main`, keeps the module
count down but leaves tests on the no-op provider and readiness blind to
flags. Recommended: the component.

## Stack

- [x] **`spec-0182-component`** ([#552](https://github.com/matthewjones372/lark/pull/552)) — `lark-app-openfeature`, `openFeature`,
      the `Client` node, readiness, release, and the dependency test.
      Done when: a graph whose provider fails init refuses to start, one
      whose provider goes `STALE` reports not ready, and release shuts the
      SDK down.

## Acceptance

```bash
./gradlew :lark-app-openfeature:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. Is `openFeature` a new factory the wiring plugin must learn (`Reader.kt`)?
   **Built from `install` and `single`**, if the checker follows it; else
   add the entry. **Answered:** as recommended.
2. `OpenFeatureAPI` is a process-wide singleton. Two graphs in one JVM (a
   test suite) would share it. Refuse a second `openFeature` while one is
   installed, or let the last one win? **Refuse**, naming the graph that
   holds it. **Answered:** as recommended.
