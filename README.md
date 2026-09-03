# Lark

Arrow's `arrow-fx-coroutines` on virtual threads. Drop the `suspend`, swap the
import, and the body stays exactly as it was:

```kotlin
// arrow-fx-coroutines
suspend fun dashboard(id: Long): Either<Err, Dashboard> = either {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}

// lark
fun dashboard(id: Long): Either<Err, Dashboard> = flock {
    parZip({ users.find(id).bind() }, { orders.forUser(id).bind() }) { u, o -> Dashboard(u, o) }
}
```

Each branch runs on a virtual thread of its own and is free to block, so a
service whose ports are JDBC or a client with no async surface gets the fork and
join without a dispatcher to starve. It is `arrow-core` plus the JDK, and
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

```kotlin
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.parMap
import io.github.matthewjones372.lark.parZip
import io.github.matthewjones372.lark.raceN

data class User(val id: Long, val name: String)

data class Order(val id: Long, val total: Long)

data class Dashboard(val user: User, val orders: List<Order>)

data class NotFound(val message: String)

// Ports written the way Arrow writes them: the failure is in the return type,
// and the call is free to block.
fun findUser(id: Long): Either<NotFound, User> =
    if (id == 1L) User(1, "Ada").right() else NotFound("No user $id").left()

fun ordersFor(id: Long): Either<NotFound, List<Order>> = listOf(Order(1, 99L)).right()

fun dashboard(id: Long): Either<NotFound, Dashboard> = flock {
    parZip({ findUser(id).bind() }, { ordersFor(id).bind() }) { user, orders ->
        Dashboard(user, orders)
    }
}

fun totals(ids: List<Long>): Either<NotFound, List<Long>> = flock {
    parMap(ids) { id -> ordersFor(id).bind().sumOf { it.total } }
}

fun quickest(id: Long): Either<NotFound, Either<User, User>> = flock {
    raceN({ findUser(id).bind() }, { findUser(id).bind() })
}

fun main() {
    println(dashboard(1L))
}
```

`flock { }` opens the scope on the calling thread and closes it when the block
leaves — by return, raise or throw. The first branch to raise or throw
interrupts its siblings; `raceN` interrupts the losers. Interrupt is the only
cancellation the JDK has, so a cancelled branch ends at its next interruptible
blocking call, and the scope returns only once every fork it owns has ended.

Virtual threads are why the floor is JDK 21. Before JDK 24 a blocking call
inside a `synchronized` block — which some JDBC drivers still make — pins its
carrier thread instead of parking it, so a service on 21 can still run out of
carriers; JEP 491 removes that pinning in 24.

## Forking by hand

`async`/`await` is the one thing `arrow-fx-coroutines` has no name for here,
because a coroutine's `async` needs a `CoroutineScope`. `Flock<E>` is that
scope, and a raise on a fork surfaces where its value is asked for:

```kotlin
import arrow.core.Either
import io.github.matthewjones372.lark.flock

fun dashboardByHand(id: Long): Either<NotFound, Dashboard> = flock {
    val user = async { findUser(id).bind() }        // on its own virtual thread
    val orders = async { ordersFor(id).bind() }     // and so is this
    Dashboard(user.await(), orders.await())         // a raise in either surfaces here
}
```

A `Deferred` nobody awaits is still joined when the scope closes, and its
failure is still the block's `Left`.

## Status

`flock { }`, `async`/`await`, `parZip`, `parMap` and `raceN` are here — all of
[`specs/0001-a-handler-that-raises.md`](specs/0001-a-handler-that-raises.md) and
[`specs/0002-a-handler-that-forks.md`](specs/0002-a-handler-that-forks.md) that
stayed. Moving the combinators onto Arrow's own `Raise`, and then
`parZipOrAccumulate`, `resourceScope` and `Schedule`, is
[`specs/0003-a-drop-in-for-arrow-fx.md`](specs/0003-a-drop-in-for-arrow-fx.md).
[`AGENTS.md`](AGENTS.md) says how work here proceeds.

## Family

- [Kestrel](https://github.com/matthewjones372/kestrel) — load simulations on
  virtual threads.
- `pelican-lark` — the binder that runs a
  [Pelican](https://github.com/matthewjones372/pelican) handler in `Raise` on a
  virtual thread, in that repository rather than this one.

## Licence

Apache 2.0.
