# 0195 — Declared flags checked at start

## Problem

0183 declares a flag as the type the code wants, and 0184 puts the flag's
variants in the service's own HOCON. Nothing compares the two until a request
reads the flag. A `Flag.duration` whose `raised` variant is `"5x"`, a
`Flag.enum` variant naming a constant that was renamed, or a `Flag.int` key
misspelt in `application.conf` all start cleanly. The first caller targeted at
that variant gets the default, with `PARSE_ERROR`, `TYPE_MISMATCH` or
`FLAG_NOT_FOUND`. The service owner finds out from a warning in the logs, maybe
days later, for only some callers. The config and the declarations sit in the
same deploy, so the mismatch is knowable before the first request, and 0182
already gives the start a place to refuse.

## Not doing

- **No check of targeting rules.** flagd already validates them against its
  schema (0184). A rule can only answer one of the flag's variants, so
  checking every variant covers whatever a rule picks.
- **No check for other providers.** Only `flagdFlags` reads its variants from
  the service's config. A remote provider's flags can change after start, so
  checking them at start would promise something false.
- **No complaint about undeclared flags.** A key in the section that no
  declaration names may belong to code not yet deployed, or to another
  service reading the same file.
- **No discovery of declarations.** No reflection, no classpath scan. The
  service lists the flags it declares.

## Shape

`lark-openfeature`: each `Flag` can say whether a raw value would decode as
its type. It uses the same decoding that `value()` uses, so the check and the
read cannot disagree.

```kotlin
class Flag<A : Any> {
    /** Why [variant] would not read as this flag's type, or null when it would. */
    fun rejects(variant: Value): String?
}

Flag.int("adoption.per-customer-limit", 3).rejects(Value("5x"))    // "is a string, not an Int"
Flag.duration("checkout.registry-timeout", 2.seconds).rejects(Value("5x"))
                                                                   // "\"5x\" did not decode as a Duration: …"
Flag.enum("checkout.mode", Checkout.Classic).rejects(Value("Classic"))   // null
```

`lark-app-openfeature-flagd`: `flagdFlags` takes the declared flags, and checks
every variant of each before the provider starts:

```kotlin
object AdoptionFlags {
    val registryCheck = Flag.boolean("adoption.registry-check", default = true)
    val perAdopterLimit = Flag.int("adoption.per-customer-limit", default = 3)
    val all = listOf(registryCheck, perAdopterLimit)
}

val flags: Module = flagdFlags("flags", AdoptionFlags.all + CheckoutFlags.all)
```

Every miss is listed in one refusal, each with the HOCON line of the variant
or the section:

```
flags at flags do not match their declarations:
  adoption.per-customer-limit: variant raised is a string, not an Int (application.conf: 14)
  checkout.mode: variant fast "Fast" did not decode as a Checkout: … (application.conf: 22)
  checkout.registry-timout: declared but not in the section (application.conf: 9)
```

`flagdFlags(path)` with no declarations stays as it is.

## Why this shape

The check lives on `Flag` because only `Flag` knows how it decodes. A
`Flag.of` decoder is the caller's own function, so the flagd module could not
check it any other way. The other option was a type tag on `Flag`, such as
`Kind.Int` or `Kind.Duration`, for the flagd module to switch on. That copies
each decoding rule into a second place, and still can't check `Flag.of`.
Listing the declarations by hand costs one line per flag object, and the list
stays next to the declarations it names. Recommended: `rejects` on `Flag`,
with an explicit list.

## Stack

- [x] **`spec-0195-flag-rejects`** (#599): `Flag.rejects(Value)` for every factory in
      `lark-openfeature`, with tests per factory. Each test has one value that
      decodes and one that doesn't.
      Done when: `rejects` is null exactly when `value()` on a provider holding
      that value answers it without an error code.
- [ ] **`spec-0195-flagd-declared`**: `flagdFlags(path, declared)`, the
      refusal with origins, and the `docs/flags.md` section. The wiring
      checker's `flagdFlags` rule accepts the second argument.
      Done when: a section with a mistyped variant, an enum variant with no
      such constant and a missing declared key refuses the start, naming all
      three with their lines. A section that matches starts, and its flags
      answer as before.

## Acceptance

```bash
./gradlew :lark-openfeature:test :lark-app-openfeature-flagd:test :lark-app-gradle:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. Should a declared flag missing from the section refuse the start, or only
   warn? **Refuse.** A missing key is usually a misspelling on one side, and
   leaving the flag out on purpose means it always answers its default. A
   declaration nobody configures should be deleted, or given a one-variant
   entry. **Answered:** as recommended.
2. Should a `Flag.int` accept a variant `5.0`? **No.** HOCON writes `5` as an
   integer. The check should say what the read would say. The first entry
   confirms how flagd's integer evaluation treats `5.0`, and matches it. **Answered:** as recommended.
3. Should `rejects` be public, or internal to the two modules? **Public.** A
   test on `InMemoryProvider`, or a provider of a service's own, can use the
   same check. It's one function, documented on `Flag`. **Answered:** as recommended.
4. Should the flags be passed as `vararg` or as a `List`? **List**, because a
   service with several flag objects joins their `all`s with `+`. A `vararg`
   overload can come later if someone asks for it. **Answered:** as recommended.
