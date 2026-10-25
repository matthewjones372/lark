# 0183 — A flag of the type the code wants

## Problem

OpenFeature knows five types: boolean, string, integer, double, object.
0181 covers the first four. A cut-over time, a timeout, a mode or a small
structure arrives as a string or an object, so each call site parses it by
hand, a bad value throws there, mid-request, and two call sites can parse the
same flag differently.

## Not doing

- **No JSON or serialization library.** An object flag is decoded from the
  SDK's `Value` by a function the caller writes.
- **No typed errors.** As 0181: a value that will not decode answers the
  default.
- **No `java.time.Duration`, `LocalDate`, `ZonedDateTime`.** `of` covers
  them until a flag needs one.

## Shape

In `lark-openfeature`, beside 0181's factories:

```kotlin
object AdoptionFlags {
    val registryTimeout = Flag.duration("adoption.registry-timeout", default = 2.seconds)
    val newRulesFrom = Flag.instant("adoption.new-rules-from", default = Instant.MAX)
    val checkout = Flag.enum("adoption.checkout", default = Checkout.Classic)
    val window = Flag.of("adoption.window", default = Window.Always) { value: Value -> Window.from(value) }
}

registry.verify(chip, within = AdoptionFlags.registryTimeout.value())
```

| Factory | Read as | Decoded by |
|---|---|---|
| `long` | string or integer | `toLong` |
| `duration` | string | `kotlin.time.Duration.parse` (`"5s"`, `"PT5S"`) |
| `instant` | string | `Instant.parse` (ISO-8601) |
| `enum<E>` | string | constant name, exact match |
| `of(key, default, decode)` | object | the caller's `(Value) -> A` |

A decode that throws answers the default, with `PARSE_ERROR` and the
exception's message in `details()`, and one warning through 0180's hook.

## Why this shape

This is where Lark adds something OpenFeature does not: the call site gets a
`Duration`, not a string it has to trust, and the parsing lives once, next to
the key and default. Derived types are read as strings because that is how
flagd and HOCON carry them. Decoding is a function, not a library, so the
module keeps its one dependency.

## Stack

- [x] **`spec-0183-decoded`** ([#578](https://github.com/matthewjones372/lark/pull/578)) — `long`, `duration`, `instant`, `enum`, `of`.
      Done when: each decodes a good value, and a bad one answers its default
      with `PARSE_ERROR` and one hook warning.
- [x] **`spec-0183-docs`** ([#579](https://github.com/matthewjones372/lark/pull/579)) — the types in `docs/flags.md`.
      Done when: the guide's test compiles the new fences.

## Acceptance

```bash
./gradlew :lark-openfeature:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. `enum` matching: exact name, or case-insensitive? **Exact**; a typo
   should be a `PARSE_ERROR`, not a guess. **Answered:** as recommended.
2. Decode on every `value()`, or cache per variant? **Every call**: parsing
   a short string is cheaper than a cache keyed on provider state. **Answered:** as recommended.
3. Check every declared flag's variants at graph start, refusing the start
   with every bad one named (as 0023 does for a config section)? It needs a
   list of declared flags, which 0181 does not keep. **Defer**: 0184's
   question 4 carries it. **Deferred.**
