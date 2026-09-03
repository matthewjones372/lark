# Lark

A lark rises. So does a Pelican handler written in Arrow's `Raise`:

```kotlin
getUser handledRaising { id ->
    users.find(id) ?: raise(UserNotFound(id))   // blocking, on this request's own virtual thread
}
```

Lark binds a [Pelican](https://github.com/matthewjones372/pelican) endpoint to
a handler that `raise`s its declared failures and blocks freely, because each
request runs on a virtual thread of its own. It is `pelican-arrow` plus the
JDK, and nothing else — no coroutines, no second effect system.

## Use it

```kotlin
dependencies {
    // the binder, bringing pelican-arrow and arrow-core with it
    implementation("io.github.matthewjones372:lark-pelican:0.1.0-SNAPSHOT")
    // the interpreter that serves the routes, and the codec that carries the bodies
    implementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC1")
    implementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC1")
}
```

An untagged commit publishes `0.1.0-SNAPSHOT`, which is what
`./gradlew publishToMavenLocal` installs; a release follows Pelican 1.0.0.

```kotlin
import arrow.core.Either
import arrow.core.left
import arrow.core.raise.ensure
import arrow.core.right
import io.github.matthewjones372.lark.pelican.handledRaising
import io.github.matthewjones372.pelican.api
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.jackson.JacksonCodecs
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.pekko.start

data class User(val id: Long, val name: String)

data class NotFound(val message: String)

val userId = pathParam<Long>("userId")

val noSuchUser = errorJson<NotFound>(404, "No user with that id")

val getUser = endpoint(userId) {
    get("users" / userId)
    summary = "Fetch a single user"
    json<User>() orFail noSuchUser
}

// A service written the way Arrow writes one: the failure is in the return
// type, and the call is free to block.
fun findUser(id: Long): Either<NotFound, User> =
    if (id == 1L) User(1, "Ada").right() else NotFound("No user $id").left()

fun main() {
    val users = api(
        endpoints = listOf(
            getUser handledRaising { id ->
                ensure(id > 0) { NotFound("Ids start at 1") }
                findUser(id).bind()
            },
        ),
        codecs = JacksonCodecs,
    )

    val server = users.start(port = 8080)
    println("Listening on ${server.baseUrl}")
}
```

The body runs on a virtual thread of its own: `raise` and `bind` answer with the
404 the endpoint declared, and anything else that escapes is the interpreter's
500. Virtual threads are why the floor is JDK 21. Before JDK 24 a blocking call
inside a `synchronized` block — which some JDBC drivers still make — pins its
carrier thread instead of parking it, so a service on 21 can still run out of
carriers; JEP 491 removes that pinning in 24.

## Naming one of several failures

The declaration is what fixes the status, so an endpoint declaring more than
one failure needs the handler to name the one it means. A declaration is
callable, and `raise` takes what it produces — with values for the headers that
failure declares.

```kotlin
import io.github.matthewjones372.lark.pelican.handledRaising
import io.github.matthewjones372.pelican.div
import io.github.matthewjones372.pelican.endpoint
import io.github.matthewjones372.pelican.errorJson
import io.github.matthewjones372.pelican.of
import io.github.matthewjones372.pelican.orFail
import io.github.matthewjones372.pelican.pathParam
import io.github.matthewjones372.pelican.responseHeader

data class Order(val id: Long)

sealed interface OrderError {
    data class NoSuchOrder(val id: Long) : OrderError

    data class OrderHidden(val id: Long) : OrderError
}

val orderId = pathParam<Long>("orderId")

val retryAfter = responseHeader<Long>("Retry-After")

val noSuchOrder = errorJson<OrderError.NoSuchOrder>(404, "No order with that id")

val forbidden = errorJson<OrderError.OrderHidden>(403, "The order is not the caller's", retryAfter)

// E widens to OrderError, the failures' common supertype, so a `when` over the
// hierarchy in the handler is exhaustive.
val getOrder = endpoint(orderId) {
    get("orders" / orderId)
    json<Order>().orFail(noSuchOrder, forbidden)
}

val orders = mapOf(1L to Order(1), 2L to Order(2))

val route = getOrder handledRaising { id ->
    val order = orders[id] ?: raise(noSuchOrder(OrderError.NoSuchOrder(id)))
    if (id == 2L) raise(forbidden(OrderError.OrderHidden(id), retryAfter of 60L))
    order
}
```

A bare `raise(error)` names no failure, which is what an endpoint declaring one
means. On this endpoint it is refused where the response is written — a 500 and
a report to `onError`, in the words bare `err(error)` is refused in.

## An executor of your own

`handledRaising(on = executor)` runs the body on a `java.util.concurrent.Executor`
the caller owns — the pool a service already sizes against the resource it
guards, or a direct one in a test — rather than on a virtual thread of its own:

```kotlin
getUser.handledRaising(on = jdbcPool) { id -> findUser(id).bind() }
```

Cancelling the stage the handler answered with interrupts the body, so it ends
at its next interruptible call; a body that has already finished is left alone,
and the cancelled stage stays cancelled.

## Status

`handledRaising` is here, which is the whole of
[`specs/0001-a-handler-that-raises.md`](specs/0001-a-handler-that-raises.md): it
binds an endpoint to a handler in `Raise`, on a virtual thread per request or on
an executor you name, answering either the single declared failure or the one a
declaration names. Forking — `async`/`await`, `parZip`, `parMap`, `raceN` — is
[`specs/0002-a-handler-that-forks.md`](specs/0002-a-handler-that-forks.md).
[`AGENTS.md`](AGENTS.md) says how work here proceeds.

## Family

- [Pelican](https://github.com/matthewjones372/pelican) — endpoints as values,
  one description for the route, the document, the client and the tests.
- [Kestrel](https://github.com/matthewjones372/kestrel) — load simulations on
  virtual threads.
- Lark — Pelican handlers in `Raise`, on virtual threads.

## Licence

Apache 2.0.
