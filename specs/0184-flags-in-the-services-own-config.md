# 0184 — Flags in the service's own config

## Problem

A service whose settings are HOCON has to keep its flags in a second format:
flagd reads JSON or YAML, and the SDK's `InMemoryProvider` is built in code.
Two formats means two places to look, two override mechanisms in tests, and
no fault accumulation for the flags.

## Not doing

- **No rule language of our own.** Flags use flagd's schema, targeting
  included.
- **No flagd server or sync.** Flags are evaluated in-process from the
  service's own config; a change is a redeploy, as with any other setting.
- **No live reload.** Same rule as 0023.

## Shape

A module, `lark-app-openfeature-flagd`, on `lark-app-openfeature`,
`lark-app-typesafe` and `dev.openfeature.contrib.providers:flagd`. HOCON is a
superset of JSON, so a flagd definition is already a valid section:

```hocon
flags {
  "adoption.registry-check" { state = ENABLED, variants { on = true, off = false }, defaultVariant = on }
  "adoption.per-customer-limit" {
    state = ENABLED
    variants { standard = 3, raised = 5 }
    defaultVariant = standard
    targeting { fractional = [[standard, 90], [raised, 10]] }
  }
}
```

```kotlin
val flags: Module = conf.flagdFlags("flags")   // 0182's openFeature, with this provider
```

`flagdFlags` renders the section to JSON and hands it to flagd's in-process
resolver through a `customConnector` that emits it once. flagd validates the
schema and evaluates JsonLogic; its `targetingKey` is 0180's `evaluatedFor`
key. Tests override the section like any other.

## Why this shape

One config format, and nothing invented: flagd's schema and JsonLogic are
documented, tested and portable to a flagd server later without rewriting a
flag. The cost is the flagd jar, which brings gRPC and netty even when only
the in-process resolver runs; it lives in its own module so a service on
another provider never carries it. The alternative, our own HOCON rules over
`InMemoryProvider`, keeps the classpath small but makes Lark own a rule
language. Recommended: flagd's schema.

## Stack

- [ ] **`spec-0184-flagd`** — `lark-app-openfeature-flagd`, `flagdFlags`,
      the dependency test.
      Done when: a targeted flag in HOCON answers per `evaluatedFor` key, and
      a section flagd rejects refuses the start naming the flag and file.

## Acceptance

```bash
./gradlew :lark-app-openfeature-flagd:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. flagd's `Connector` has changed between releases. Use it, or write the
   JSON to a temp file for the `FILE` resolver? **Connector**, pinning the
   flagd version: no file, no 5s poll of something that never changes. **Answered:** as recommended.
2. flagd's schema errors name JSON paths, not HOCON lines. Wrap them with
   the section's origin (`application.conf: 12`)? **Yes**. **Answered:** as recommended.
3. Does `com.typesafe:config`, arriving through `lark-app-typesafe`, count
   against the one-external-library rule? **No**, as `arrow-core` through
   `lark` does not. **Answered:** as recommended.
4. Decode 0183's declared flags' variants at start
   (`flagdFlags("flags", AdoptionFlags.all)`)? **Defer** to a spec of its
   own once 0183 ships. **Deferred.**

Petshop follow-up: a `flags` section in `application.conf`; `flagdFlags` in
the graph; `evaluatedFor(by)` at `Adoption.adopt`; tests override the section.
No flagd container in the demo.
