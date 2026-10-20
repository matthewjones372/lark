# 0142 — The reader knows lark-app-actor

## Problem

The compiler plugin's reader (`lark-app-compiler`, `Reader.kt`) knows `lark-app-pekko`'s `actor` by name,
but not `lark-app-actor`'s `actors()` and `actor`. A library function has no body in the source being
compiled, so the reader gives up on any application that uses them and reports `LARK_APP_UNREAD`:

```
lark-app: this graph was not read here, and is checked by larkWiring alone:
io/github/matthewjones372/lark/app/actor/actors, which is not a lark factory
```

Petshop hits this on every compile: its `theShop` module is `actors() + actor<Shop, Outbox>("shop") { ... }`,
so the whole `Petshop` graph goes unread, and a missing key there waits for `larkWiring` instead of
showing in the editor. `docs/app.md` lists the gap. lark-app-actor is the actor module lark recommends
over Pekko, so the reader knows the one it recommends less.

## Not doing

- **No `lark-app-cluster`'s `cluster`.** It stays unread; a spec of its own if someone hits it.
- **No reading library bodies in general** (from metadata or class files). The reader keeps listing lark's
  factories by name, as `SOURCES` and the Pekko `actor` already are.
- **No change to what lark-app-actor builds.** Only the reader's copy of it.

## Shape

In `Reader.kt`'s `factory`, two more entries beside the Pekko `actor`:

```kotlin
// actors(onDeadLetter): a node providing Actors, needing nothing.
called == ACTORS -> node("$APP.actor.Actors", emptyList(), at)

// actor<M>, actor<M, D1>, actor<M, D1, D2>: ActorRef<M>, needing Actors and each D.
called == LARK_ACTOR -> key?.let { node("$LARK_ACTOR_REF<$it>", listOf(ACTORS_KEY) + rest, at) }
```

with `ACTORS = extension("actor", "actors")`, `LARK_ACTOR = extension("actor", "actor")` and
`LARK_ACTOR_REF = "io.github.matthewjones372.lark.actor.ActorRef"`, the keys `single` in `Actors.kt`
produces at runtime.

`LarkWiringPluginTest` compiles a second library graph against the real `lark-app-actor`
(`larkAppUnderTest(project(":lark-app-actor"))`):

```kotlin
actors() + single<Ledger> { Ledger() } + actor<Ping, Ledger>("ping") { _ -> pinged() }
```

and expects `provides [ActorRef<Ping>, Actors, Ledger], and is short of []`. `docs/app.md` moves
`lark-app-actor`'s `actor` and `actors()` from the unread list to the read one.

## Why this shape

Listing the two factories matches how the reader already treats every lark library factory: what each
builds is lark's own API, and the test that compiles against the real module is what catches the copy
going stale. The alternative, reading the inline `actor`'s body from its metadata, would cover future
factories for free but means a second reader for compiled code, which the reader's "give up rather than
guess" rule makes risky. Recommended: list them.

## Stack

- [ ] **`spec-0142-reader-actors`**: `actors` and lark-app-actor's `actor` in the reader, the library test,
      and `docs/app.md`.
      Done when: the new `LarkWiringPluginTest` case passes, and petshop's `Petshop` compiles with
      `larkWiring { verbose = true }` saying what it provides instead of `LARK_APP_UNREAD`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :lark-app-gradle:test --tests '*LarkWiringPluginTest*'
```

## Open questions

1. **Land before or after 0139's module moves?** Recommended: after. 0139 moves `lark-app-actor` and the
   gradle module into family folders (#406 to #409), and this touches both; rebasing a small change onto
   the moves is cheaper than the other way round.
2. **Should `actors()` with an `onDeadLetter` argument be read the same?** Recommended: yes. The argument
   changes no key, and `factory` decides by name and type arguments alone.
