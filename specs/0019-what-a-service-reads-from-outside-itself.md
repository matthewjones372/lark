# 0019 — What a service reads from outside itself

## Problem

A node that calls `System.getenv` is a node no test can configure. The value
comes from the process, so a test of what a service does with a bad port either
sets an environment variable for the whole JVM or does not run.

And spec 0016 left `probe` without a retry, because backing off needed a clock
a test could move. Spec 0017 built one.

## Not doing

- **No decoder.** A config type is built by the service, from values read here.
  Where several reads have to answer at once, `parZipOrAccumulate` already does
  it and says every bad name rather than the first.
- **No file or HOCON source.** `Sys` is the process; a file is a node that
  reads one.
- **No `Random` or `Console`.** Same shape when something needs them.

## Shape

```kotlin
interface Sys {
    fun env(name: String): String?
    fun property(name: String): String?
    fun env(): Map<String, String>
}
```

```kotlin
single<Sys> { RealSys }
single { sys: Sys -> AppConfig(sys.required("DB_URL").bind(), sys.int("PORT").bind()) }
```

```kotlin
sys.required("DB_URL")   // Either<ConfigError, String>
sys.int("PORT")          // ConfigError.NotA("PORT", "an Int", "eighty-eighty")
sys.duration("WAIT")
sys.boolean("DEBUG")
sys.optional("REGION")   // String?, for a value with a default
```

A test replaces the process with a map:

```kotlin
testApp(app.overriding(single<Sys> { FakeSys(mapOf("PORT" to "8080")) })) { … }
```

And a probe may be asked more than once:

```kotlin
.probe("kafka", timeout = 2.seconds, attempts = 10, interval = 500.milliseconds) { it.assigned() }
```

## Why this shape

Attempts rather than a deadline. A deadline is the natural way to say it and
the wrong way to build it: under `fixedClock` the clock never reaches one, so a
probe that never passes never stops. A count terminates whatever the clock
does, and the wait between attempts still goes through the bound clock, so a
test of ten attempts takes microseconds.

`Sys` returning `Either` rather than raising: a service reads several names and
wants every complaint at once, which is `parZipOrAccumulate`'s shape, not a
raise's.

## Stack

- [ ] **`spec-0019-services`** — `Sys`, `RealSys`, `FakeSys`, the readers, and
      `probe`'s `attempts` and `interval`.
      Done when: a bad port names itself and what it was, and a probe passing on
      its third attempt does not fail the start.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `Sys` belong in `lark-app` or in `lark`?** Recommend `lark-app`: it
   is a dependency a node takes, and `lark` has no notion of a node.
2. **Should `probe` also accept a `Schedule`?** Recommend not yet. `Schedule`
   answers with its own output rather than the last thing the action returned,
   so wiring it to "stop when true" reads worse than the count it replaces.
