# 0142 — The reader knows surefold-app-actor

## Problem

The compiler plugin's reader (`surefold-app-compiler`, `Reader.kt`) knows `surefold-app-pekko`'s `actor` by name,
but not `surefold-app-actor`'s `actors()` and `actor`. A library function has no body in the source being
compiled, so the reader gives up on any application that uses them and reports `SUREFOLD_APP_UNREAD`:

```
surefold-app: this graph was not read here, and is checked by surefoldWiring alone:
io/github/matthewjones372/surefold/app/actor/actors, which is not a surefold factory
```

Petshop hits this on every compile: its `theShop` module is `actors() + actor<Shop, Outbox>("shop") { ... }`,
so the whole `Petshop` graph goes unread, and a missing key there waits for `surefoldWiring` instead of
showing in the editor. `docs/app.md` lists the gap. surefold-app-actor is the actor module surefold recommends
over Pekko, so the reader knows the one it recommends less.

## Not doing

- **No `surefold-app-cluster`'s `cluster`.** It stays unread; a spec of its own if someone hits it.
- **No reading library bodies in general** (from metadata or class files). The reader keeps listing surefold's
  factories by name, as `SOURCES` and the Pekko `actor` already are.
- **No change to what surefold-app-actor builds.** Only the reader's copy of it.

## Shape

In `Reader.kt`'s `factory`, two more entries beside the Pekko `actor`:

```kotlin
// actors(onDeadLetter): a node providing Actors, needing nothing.
called == ACTORS -> node("$APP.actor.Actors", emptyList(), at)

// actor<M>, actor<M, D1>, actor<M, D1, D2>: ActorRef<M>, needing Actors and each D.
called == SUREFOLD_ACTOR -> key?.let { node("$SUREFOLD_ACTOR_REF<$it>", listOf(ACTORS_KEY) + rest, at) }
```

with `ACTORS = extension("actor", "actors")`, `SUREFOLD_ACTOR = extension("actor", "actor")` and
`SUREFOLD_ACTOR_REF = "io.github.matthewjones372.surefold.actor.ActorRef"`, the keys `single` in `Actors.kt`
produces at runtime.

`SurefoldWiringPluginTest` compiles a second library graph against the real `surefold-app-actor`
(`surefoldAppUnderTest(project(":surefold-app-actor"))`):

```kotlin
actors() + single<Ledger> { Ledger() } + actor<Ping, Ledger>("ping") { _ -> pinged() }
```

and expects `provides [ActorRef<Ping>, Actors, Ledger], and is short of []`. `docs/app.md` moves
`surefold-app-actor`'s `actor` and `actors()` from the unread list to the read one.

## Why this shape

Listing the two factories matches how the reader already treats every surefold library factory: what each
builds is surefold's own API, and the test that compiles against the real module is what catches the copy
going stale. The alternative, reading the inline `actor`'s body from its metadata, would cover future
factories for free but means a second reader for compiled code, which the reader's "give up rather than
guess" rule makes risky. Recommended: list them.

## Stack

- [x] **`spec-0142-reader-actors`**: `actors` and surefold-app-actor's `actor` in the reader, the library test,
      and `docs/app.md`.
      Done when: the new `SurefoldWiringPluginTest` case passes, and petshop's `Petshop` compiles with
      `surefoldWiring { verbose = true }` saying what it provides instead of `SUREFOLD_APP_UNREAD`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-app-gradle:test --tests '*SurefoldWiringPluginTest*'
```

## Open questions

1. **Land before or after 0139's module moves?** Recommended: after. 0139 moves `surefold-app-actor` and the
   gradle module into family folders (#406 to #409), and this touches both; rebasing a small change onto
   the moves is cheaper than the other way round.
2. **Should `actors()` with an `onDeadLetter` argument be read the same?** Recommended: yes. The argument
   changes no key, and `factory` decides by name and type arguments alone.
