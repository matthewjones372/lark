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

## Status

The build is here and the binder is on its way. [`specs/0001-a-handler-that-raises.md`](specs/0001-a-handler-that-raises.md)
says what will be true when it lands; [`AGENTS.md`](AGENTS.md) says how work
here proceeds.

## Family

- [Pelican](https://github.com/matthewjones372/pelican) — endpoints as values,
  one description for the route, the document, the client and the tests.
- [Kestrel](https://github.com/matthewjones372/kestrel) — load simulations on
  virtual threads.
- Lark — Pelican handlers in `Raise`, on virtual threads.

## Licence

Apache 2.0.
