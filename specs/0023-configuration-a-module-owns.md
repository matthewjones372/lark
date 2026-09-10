# 0023 — Configuration a module owns

## Problem

Spec 0019 gave a node the process rather than `System.getenv`, and stopped
there. `Sys` is the environment and nothing else, so a service reading a YAML
file, a Typesafe `Config` or Hoplite's decoding still reads it in `main`, builds
one type that knows about every section, and hands slices of it down.

That type is the coupling. Adding a Kafka consumer means editing the root
config type, the file it is decoded from, and the wiring — three places, none of
them the module that wanted the setting. And a bad deploy answers with the first
fault rather than all of them.

## Not doing

- **No decoder generation.** No reflection and no processor: the type is built
  by code the compiler checks, which is where the safety is.
- **No file formats.** YAML, HOCON and properties are adapters, and none of
  them is in `lark-app`.
- **No live reload.** A value read once at start-up is what a graph is built
  from; a value that changes under a running node is a different spec.

## Shape

```kotlin
fun interface Config {
    fun at(path: String): String?
}
```

One method, the way `Logger` has one. Hoplite, Typesafe Config, a properties
file and the environment are each one implementation.

A section is a node, read by the module that needs it:

```kotlin
val database: Module =
    configured("database") { DbSettings(string("url"), int("poolSize"), optional("ssl", false) { boolean(it) }) } +
    single { db: DbSettings -> install({ Hikari(db) }) { pool, _ -> pool.close() } as Pool }
```

Every fault at once, because a read that cannot answer records why and the
block runs on:

```
lark-app: DbSettings refused to start: database.url is not set;
database.poolSize is not an Int: lots
```

And the `when` a service writes over its own settings is a value:

```kotlin
settings.choose("cartStore", default = "redis", "redis" to redisCarts, "postgres" to postgresCarts)
```

## Why this shape

A section per module rather than one root type. The safety a reader wants is
that `DbSettings` is a real type with real fields, and that is true either way;
what a root type costs is that every module's settings are in one file that
every module has to be edited alongside. A module that brings its own reading
can be added and removed whole.

`choose` decides while the graph is being described rather than while it is
running, so the branch not taken contributes no node, nothing to build and
nothing to start — a service on Postgres never opens a Redis connection, and
`render` draws the shape that deployment actually has.

The accumulation is a single pass with a recorded fault and a discarded value,
rather than an applicative `zipOrAccumulate`. It reads as ordinary construction,
which is the whole point; the cost is that the block must be pure, and a
constructor that validates its arguments may throw on the discarded values —
caught, with the faults answered instead.

## Stack

- [x] **`spec-0023-config`** — `Config`, `configOf`, `orElse`, `Sys.asConfig`,
      `Reading`, `read`, `configured`, `choose`.
      Done when: a section refuses the start naming every fault at once, and a
      module the configuration did not choose contributes no node.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `Sys` survive?** Recommend yes, narrowed: it is the process, and
   `asConfig` is the bridge. A node wanting `PATH` rather than a setting still
   has somewhere honest to ask.
2. **Lists and maps?** Not yet. `string("brokers")` split by the caller covers
   what a service actually reads, and a repeated-key convention is a decision
   each format makes differently.
3. **Which adapter first?** Recommend Typesafe Config: it is what Pekko already
   configures itself from, so a service on `lark-app-pekko` has one file.
