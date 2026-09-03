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

## Every error, not the first one

`parZipOrAccumulate` and `parMapOrAccumulate` run every branch to completion and
answer with all of the raises rather than the first: a form asking whether each
field is valid wants every complaint at once, not the earliest one. The scope
declares a `NonEmptyList` of the branches' error, and these are every import:

```kotlin
import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.either
import io.github.matthewjones372.lark.parMapOrAccumulate
import io.github.matthewjones372.lark.parZipOrAccumulate

fun validate(form: Form): Either<NonEmptyList<Err>, Account> = either {
    parZipOrAccumulate({ name(form).bind() }, { email(form).bind() }) { n, e -> Account(n, e) }
}

fun accept(rows: List<Row>): Either<NonEmptyList<Err>, List<Entry>> = either {
    parMapOrAccumulate(rows) { entry(it).bind() }
}
```

Both take a `combine: (Err, Err) -> Err` as their first argument instead, for a
scope that declares one error and knows how to fold two into it. The errors
answer in branch order, and in the iterable's order, whichever branch raised
first. A throw is not accumulated: it ends the other branches as `parZip`'s
does, and the same instance is rethrown.

## Things that have to be given back

`resourceScope { }` runs a block with resources acquired in it, and releases
them in reverse acquisition order on the way out — by return, raise, throw or
interrupt. `install` names the pair, and the release is told which of those
happened:

```kotlin
import arrow.core.Either
import arrow.core.raise.either
import io.github.matthewjones372.lark.ExitCase
import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.resource
import io.github.matthewjones372.lark.resourceScope
import io.github.matthewjones372.lark.use

val connection: Resource<Connection> = resource {
    install({ pool.take() }) { held, exit ->
        if (exit is ExitCase.Failure) held.rollback() else held.commit()
        held.close()
    }
}

fun report(id: Id): Either<Err, Report> = either {
    resourceScope {
        val db = connection.bind()          // its release joins this scope's
        val file = install({ open(path) }) { it, _ -> it.close() }
        render(db.rows(id).bind(), file)    // a raise here releases both, in reverse
    }
}

fun rows(id: Id): List<Row> = connection use { it.rows(id) }
```

`ExitCase.Cancelled` carries the `InterruptedException`, because interrupt is
the only cancellation the JDK has. A raise gets `ExitCase.Completed`: it is not
an interrupt, and the `either` it leaves through answers its caller with a
value, so nothing about the scope failed. A release that throws when nothing
else had is the failure the caller sees, and one that throws after a failure is
suppressed onto it; either way the releases after it still run.

Virtual threads are why the floor is JDK 21. Before JDK 24 a blocking call
inside a `synchronized` block — which some JDBC drivers still make — pins its
carrier thread instead of parking it, so a service on 21 can still run out of
carriers; JEP 491 removes that pinning in 24.

## Status

`flock { }`, `async`/`await`, `parZip`, `parMap` and `raceN` on `Raise`, and
`parZipOrAccumulate`/`parMapOrAccumulate` and `resourceScope` are here — all of
[`specs/0001-a-handler-that-raises.md`](specs/0001-a-handler-that-raises.md) and
[`specs/0002-a-handler-that-forks.md`](specs/0002-a-handler-that-forks.md) that
stayed, and the first four entries of
[`specs/0003-a-drop-in-for-arrow-fx.md`](specs/0003-a-drop-in-for-arrow-fx.md).
`Schedule` and `timeout` are the rest of it.
[`AGENTS.md`](AGENTS.md) says how work here proceeds.

## Family

- [Kestrel](https://github.com/matthewjones372/kestrel) — load simulations on
  virtual threads.
- `pelican-lark` — the binder that runs a
  [Pelican](https://github.com/matthewjones372/pelican) handler in `Raise` on a
  virtual thread, in that repository rather than this one.

## Licence

Apache 2.0.
