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

## Status

`handledRaising` is here: it binds an endpoint to a handler in `Raise`, one
virtual thread per request, with the single declared failure answered from a
`raise`. Naming one of several declared failures and choosing the executor are
the rest of [`specs/0001-a-handler-that-raises.md`](specs/0001-a-handler-that-raises.md);
forking — `async`/`await`, `parZip`, `parMap`, `raceN` — is
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
