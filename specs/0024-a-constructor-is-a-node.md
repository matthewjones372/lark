# 0024 — A constructor is a node

## Problem

Two services were wired with `lark-app` and both came out longer than the code
they replaced: a shopping cart went 272 lines to 283, a petshop needed about
ninety lines to describe eight nodes where sixty would have done by hand. The
measurement is the same in both: a node costs about three lines where a call
costs one.

The three lines are a restatement. `single { ds: DataSource, log: Log ->
PgUserRepo(ds, log) }` says what `PgUserRepo`'s constructor already said, in a
second place, with the types written out again.

And giving the key as a type argument does not work when there are
dependencies: `single<UserRepo> { ds: DataSource -> … }` does not compile,
because Kotlin has no partial type-argument inference. That has caught the
author three times in three codebases, which is a design fault rather than a
mistake.

## Not doing

- **No reflection.** A constructor reference is a typed function; its parameter
  types are its dependencies, and nothing has to read a `KClass` to find them.
- **No property injection, no field injection.**
- **No `singleOf` for a factory with default arguments.** A reference to one
  loses the defaults, which is a surprise rather than a feature.

## Shape

```kotlin
val persistence: Module =
    singleOf(::Hikari).boundTo<Pool>() +
    singleOf(::PgUserRepo).boundTo<UserRepo>() +
    singleOf(::RegisterUser)
```

- `singleOf(create: (D1, …) -> A): Module`, nought to nine, reading the key off
  the return type and the dependencies off the parameters.
- `Module.boundTo<B>(): Module`, re-keying the one node in a module, with any
  probe asked of it following.

## Why this shape

`boundTo` rather than a type argument on `singleOf`. The trap is that a key
given as a type argument forces every dependency to be one too; a combinator
after the fact takes no type arguments from the dependencies at all, so the
trap has nowhere to happen.

`boundTo` on a module of one node rather than on a node: `Module` is the type
callers hold, and refusing a module of several is one `require` with a count in
it, against a second type nobody asked for.

## Stack

- [x] **`spec-0024-constructors`** — `singleOf` nought to nine, `boundTo`, the
      probe following its key, the cookbook rows.
      Done when: a graph of constructor references starts, and re-keying a
      module of two nodes is refused with the count.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `singleOf` make the wiring shorter in a service?** Unknown until
   petshop is rewritten with it, which is the point of having a petshop. That
   number goes in petshop's README either way.
2. **Should `single`'s no-dependency overload go?** Recommend not: `single<Sys>
   { RealSys }` is a value rather than a constructor, and `singleOf { RealSys }`
   reads worse.
