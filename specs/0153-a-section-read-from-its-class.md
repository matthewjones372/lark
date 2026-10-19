# 0153: a section read from its class

## Problem

Every service writes its section twice: once as the class, and once as a reader
block that lists each property again under its path.

```kotlin
data class Settings(val host: String, val port: Int, val arrivalsEvery: Duration, val outboxEvery: Duration)

private val settings: Module =
    config<Settings>("petshop") {
        Settings(string("host"), int("port"), duration("arrivalsEvery"), duration("outboxEvery"))
    }
```

The block is pure repetition. When a property is added to the class but not to
the block, compilation fails. When a path string is mistyped, the fault only
shows up at start.

## Not doing

- A lark compiler plugin that generates readers. The kotlinx.serialization
  plugin already generates a descriptor for each class, so lark won't maintain
  a second code generator.
- Changes to `config(path) { … }`, `choosing` or `Reading`. The block form
  stays for anything a class can't say.
- Enums, polymorphism and lists of objects. They are listed under Open
  questions.
- Any kotlinx dependency in `lark-app-typesafe`.

## Shape

A new module, `lark-app-kotlinx`, depending on `lark-app-typesafe` and on
`kotlinx-serialization-core` only:

```kotlin
@Serializable
data class Settings(
    val host: String,
    val port: Int = 8080,                        // a default makes the path optional
    val arrivalsEvery: Duration,                 // HOCON's own `30s`, not ISO-8601
    val proxy: String? = null,                   // nullable and absent reads as null
    val password: Secret,                        // masks itself, as `secret(path)` does
    val db: Db,                                  // a nested class is a nested section
)

private val settings: Module = config<Settings>("petshop")
private val kebab: Module = config<Settings>("petshop", Naming.Kebab)   // arrivals-every
```

- The decoder sits on top of `Reading`, so a bad file still reports every
  fault in one message, and the start is refused exactly as the block form
  refuses it.
- By default a key is the property's own name. `Naming.Kebab` switches every
  key to kebab-case, and `@SerialName` overrides a single property.
- Supported leaves are `String`, `Int`, `Long`, `Boolean`, `Duration`,
  `List<String>` and `Secret`.
- `Config.choosing(path, chosen)` gets the same no-block overload.

## Why this shape

kotlinx.serialization gives lark a compile-time descriptor of the class for the
cost of one `@Serializable` annotation. A lark-owned `Decoder` on top of
`Reading` keeps what `kotlinx-serialization-hocon` would drop: it reports every
fault instead of stopping at the first, reads HOCON's durations, and keeps
`Secret`. The alternatives were a lark K2 plugin, which means IR codegen
maintained on every Kotlin bump, and `kotlin-reflect`, which is a new
dependency with nothing checked at compile time. Both were rejected.

## Stack

- [x] **`spec-0153-module`** ([#426](https://github.com/matthewjones372/lark/pull/426)):
      `lark-app-kotlinx` with a `Decoder` on
      `Reading` for the leaves, nested sections, defaults and nulls.
      `config<A>(path)` is added.
      Done when: a class with every supported leaf reads from HOCON, and a
      file with three faults is refused naming all three.
- [x] **`spec-0153-naming`** ([#427](https://github.com/matthewjones372/lark/pull/427)):
      `Naming.Kebab`, `@SerialName` and `Secret`,
      plus the `choosing` overload.
      Done when: `arrivalsEvery` reads `arrivals-every` under `Naming.Kebab`,
      and a `Secret` property is masked in `toString` and in the report.
- [x] **`spec-0153-docs`** ([#428](https://github.com/matthewjones372/lark/pull/428)):
      the cookbook's configuration recipe shows the
      class form first. `docs/app.md` and `docs/modules.md` list the module.
      Done when: `CookbookTest` compiles the new fence.

## Acceptance

```bash
./gradlew :lark-app-kotlinx:test :lark-app-typesafe:test :lark-app:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. `Secret` lives in `lark-app-typesafe` and isn't `@Serializable`. Should the
   decoder special-case it by type, or should `lark-app-typesafe` ship a
   serializer for it? I recommend special-casing it in the decoder, so
   `lark-app-typesafe` stays free of kotlinx.
2. kotlinx's built-in `Duration` serializer expects ISO strings. Should the
   decoder intercept `kotlin.time.Duration` by descriptor name, or require
   `@Contextual`? I recommend intercepting by name: no annotation, and it reads
   `30s` the way `duration(path)` does.
3. `bytes(path)` returns a `Long`, so a class can't say "this Long is a memory
   size". Should there be a `Bytes` value class now, or should size readers
   keep the block form for the moment? I recommend the block form for now.
4. Should enums be in the first entry? They are cheap through the descriptor,
   but the answer depends on how a bad value should be worded. I recommend
   entry 2, with a fault naming the allowed values.
