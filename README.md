# Lark

`arrow-fx-coroutines` on virtual threads. Drop the `suspend`, swap the import,
and the body stays exactly as it was — the combinators take Arrow's own
`Raise`, so code already inside `either { }` needs no scope of lark's around it.

Each branch runs on a virtual thread of its own and is free to block, so a
service whose ports are JDBC or a client with no async surface gets the fork and
the join without a dispatcher to starve. It is `arrow-core` plus the JDK, and
nothing else — no coroutines, no second effect system.

## Use it

```kotlin
dependencies {
    // arrow-core comes with it; nothing else does
    implementation("io.github.matthewjones372:lark:0.1.0-SNAPSHOT")
}
```

An untagged commit publishes `0.1.0-SNAPSHOT`, which is what
`./gradlew publishToMavenLocal` installs.

Before, on `arrow-fx-coroutines`:

```kotlin
suspend fun dashboard(id: Id): Either<Err, Dashboard> = either {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}
```

After, on lark — `Err`, `Dashboard`, `users` and `orders` are the service's own,
and these are every import the function needs:

```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.parZip

fun dashboard(id: Id): Either<Err, Dashboard> = either {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}
```

`parZip` takes two branches through nine, as `arrow-fx-coroutines` does. Beside
it, one line each:

```kotlin
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.parMap
import io.github.matthewjones372.lark.raceN

// every element on a fork of its own, answered in the iterable's order
fun totals(ids: List<Id>): Either<Err, List<Total>> = either { parMap(ids) { total(it).bind() } }

// the first branch to answer wins, on the side it was given
fun quote(id: Id): Either<Err, Either<Quote, Quote>> = either { raceN({ fast.quote(id) }, { slow.quote(id) }) }

// async and await, for a fork the combinators do not shape
fun user(id: Id): Either<Err, User> = either { flock { async { users.find(id).bind() }.await() } }
```

`flock { }` is the scope `async` forks in and the one thing
`arrow-fx-coroutines` has no name for here; a `Deferred` nobody awaits is still
joined when the scope closes, and its raise is still the block's `Left`. Outside
any `Raise`, `parZip`, `parMap` and `raceN` take plain `() -> A` branches and
answer with the combined value.

The first branch to raise or throw interrupts its siblings; `raceN` interrupts
the losers. Interrupt is the only cancellation the JDK has, so a cancelled
branch ends at its next interruptible blocking call, and a combinator returns
only once every fork it opened has ended.

Virtual threads are why the floor is JDK 21. Before JDK 24 a blocking call
inside a `synchronized` block — which some JDBC drivers still make — pins its
carrier thread instead of parking it, so a service on 21 can still run out of
carriers; JEP 491 removes that pinning in 24.

## Status

`flock { }`, `async`/`await`, and `parZip`, `parMap` and `raceN` on `Raise` are
here — all of
[`specs/0001-a-handler-that-raises.md`](specs/0001-a-handler-that-raises.md) and
[`specs/0002-a-handler-that-forks.md`](specs/0002-a-handler-that-forks.md) that
stayed, and the first two entries of
[`specs/0003-a-drop-in-for-arrow-fx.md`](specs/0003-a-drop-in-for-arrow-fx.md).
`parZipOrAccumulate`, `resourceScope` and `Schedule` are the rest of it.
[`AGENTS.md`](AGENTS.md) says how work here proceeds.

## Family

- [Kestrel](https://github.com/matthewjones372/kestrel) — load simulations on
  virtual threads.
- `pelican-lark` — the binder that runs a
  [Pelican](https://github.com/matthewjones372/pelican) handler in `Raise` on a
  virtual thread, in that repository rather than this one.

## Licence

Apache 2.0.
