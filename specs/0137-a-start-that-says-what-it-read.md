# 0137 — A start that says what it read

## Problem

The line worth having on every deploy is which settings the service started
with and where each came from. `lark-app-typesafe` can say it
(`layeredConfig().origins().report()`), but a service has to wire it by hand:
hold the layers, cut each to its own section so the JVM's properties and every
library's `reference.conf` stay out, and list the secrets a name match misses.
Petshop's took a field, a function, a section filter and a mask list, inside
its `LarkApp`.

## Not doing

- No logging on lark's part. `report()` answers text; the service logs it where
  it wants.
- No report of settings `application.conf` does not declare.
- No change to how a section is read, and `loadedConfig()` stays as it is.

## Shape

`loadConfig()` returns a `LoadedConfig`, the layers a service reads, and a
`LoadedConfig` plus a module is that module with the configuration in it, as
the `Config` node every `config(path)` reads:

```kotlin
val config = loadConfig()

val petshop: Module = config + infrastructure + shop + events + web

fun main() {
    logInfo(config.report())
    exitProcess(runApp(Petshop).code)
}
```

`report()` is every setting `application.conf` declares, beside the layer its
value came from and what it overrode. Values whose names say they are secrets
(`Secrets.default`) are masked; `report(alsoMask = "petshop.database.url")`
masks one a name cannot catch:

```
lark-app configuration

petshop.bus.kind           in-process   application.conf:26
petshop.database.password  ●●●●●●       application.conf:18
petshop.port               8091         PORT, overrides 8080 at application.conf:6
```

`loadedConfig()` stays, as `loadConfig()`'s module alone.

## Why this shape

A value rather than a module or a flag: the service holds what it loaded, asks
it for the report, and logs it where it chooses. In `main`, before the graph
starts, it is there even when the start fails, which is when it is wanted.
Scoped by `application.conf` rather than by a prefix or by what the graph
reads: the service's own file is the list of settings it has, a library's
`reference.conf` and the JVM's properties stay out without a filter, and the
report needs nothing from the graph. `config + module` rather than
`config.module`: `Module` is final, so a `LoadedConfig` cannot be one, but it can
read as one where the graph is assembled.

## Stack

- [ ] **`spec-0137-load-config`**: `loadConfig()`, `LoadedConfig`, and
      `LoadedConfig + Module`, with `loadedConfig()` defined through it.
      Done when: a test assembles `loadConfig() + config<Settings>(…) { … }`
      and reads the section, and every existing `loadedConfig()` test passes.
- [ ] **`spec-0137-report`**: `report()` and `report(alsoMask = …)`.
      Done when: tests show a report naming each setting `application.conf`
      declares, its layer and what it overrode, nothing from `reference.conf`
      or an unrelated system property, a password masked by name and a URL
      masked by `alsoMask`.
- [ ] **`spec-0137-docs`**: the cookbook's configuration-report section leads
      with `loadConfig()` and `report()`, keeping `origins()` for a document
      outside a service.
      Done when: `CookbookTest` and `ReportedTest` pass.

## Acceptance

```bash
./gradlew :lark-app-typesafe:build :lark-app-pekko:test
```

## Open questions

1. **A setting overridden by a `-D` or an environment variable but not declared
   in `application.conf`** is not in the report. Recommend: that is right; a
   setting a service does not declare is one it does not read.
2. **`alsoMask` as `vararg String`, or a `Secrets`** as `origins` takes?
   Recommend `vararg String`, the common case, with an overload taking
   `Secrets`.
