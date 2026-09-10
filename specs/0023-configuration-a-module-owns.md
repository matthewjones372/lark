# 0023 — Configuration a module owns

## Problem

Spec 0019 gave a node the process rather than `System.getenv`, and stopped
there. A service reading HOCON still reads it in `main`, builds one type that
knows about every section, and hands slices of it down. That type is the
coupling: adding a Kafka consumer means editing the root config type, the file
it is decoded from, and the wiring — three places, none of them the module that
wanted the setting.

Typesafe Config also stops at the first fault. Four wrong settings is four
deploys.

## Not doing

- **No facade over `com.typesafe.config.Config`.** Drafted, built, and thrown
  away: reducing every source to `String?` loses substitution, merging, lists,
  objects, `getDuration` and `getMemorySize`, and re-implements the parsing
  Typesafe Config already does better. A view of a library that is already the
  standard is a worse copy of it.
- **No decoder generation.** No reflection and no processor.
- **No `choose`.** `Module` is a value, so `when` over a setting already
  answers with one. An API for that is an API for `when`.
- **No live reload.**

## Shape

A module, `lark-app-typesafe`, on `lark-app` and `com.typesafe:config`.

```kotlin
val database: Module =
    configured("database") {
        Db(string("url"), int("poolSize"), of(Duration.ZERO) { getDuration("idle") }, strings("replicas"))
    } +
    single { db: Db -> install({ Hikari(db) }) { pool, _ -> pool.close() } as Pool }
```

`of` hands the caller the real `Config` and keeps whatever it returns, so
everything HOCON can do is still reachable — the named readers are shorthand,
not a wall. What is added is that a failed read records its fault and the block
runs on:

```
lark-app: Db refused to start: No configuration setting found for key 'url'
(application.conf: 8); ...
```

Typesafe Config's own message, which names the file and the line.

## Why this shape

A section per module rather than one root type. The safety is that `Db` is a
real type with real fields, and that is true either way; what a root type costs
is that every module's settings live in one place every module has to be edited
alongside. A module that brings its own reading can be added and removed whole.

The accumulation is a single pass with a recorded fault and a discarded value
rather than an applicative zip, so it reads as ordinary construction. The cost
is that the block must be pure, and a constructor validating its arguments may
throw on the discarded values — caught, with the faults answered instead.

## Stack

- [x] **`spec-0023-typesafe`** — `Reading`, `of`, the named readers, `reading`,
      `configured`, and the dependency test.
      Done when: a substitution, a list and a duration survive the reading, and
      a section with four faults names all four.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `Sys` survive?** Recommend yes: it is the process, not a config
   library, and it is what makes reading `PATH` testable without setting a
   JVM-wide environment variable.
2. **A Hoplite module too?** Recommend only if someone wants it. Hoplite already
   accumulates, so the only thing left to add there is the section-as-a-node,
   which is `single` and needs nothing.
