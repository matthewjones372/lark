# Coming from Arrow

**Code written with Arrow moves to Lark with a few renames, and code that keeps
both runs through `lark-arrow`.** Lark's typed errors follow the model Arrow
made familiar in Kotlin, and its combinators keep the names and shapes of
`arrow-fx-coroutines`. Lark's own types are written for blocking code on
virtual threads, so its core depends on nothing but the Kotlin standard library.

## The renames

| Arrow | Lark |
|---|---|
| `either { }` | `attempt { }` |
| `raise(error)` | `fail(error)` |
| `Raise<E>` | `Fail<E>` |
| `context(_: Raise<E>)` | `context(_: Fail<E>)` |
| `ensure`, `ensureNotNull`, `bind`, `recover` | the same names, from `io.github.matthewjones372.lark.fail` |
| `arrow.core.Either`, `NonEmptyList` | `io.github.matthewjones372.lark.fail.Either`, `NonEmptyList` |
| `Option<A>` | `A?` |
| `Either.catch { }` | a `try`, with `nonFatalOrThrow()` on what it caught |
| `parZip`, `parMap`, `raceN`, `parZipOrAccumulate`, `parMapOrAccumulate` | the same names in `io.github.matthewjones372.lark`, without `suspend` |

<!-- arrow-renames -->
```kotlin
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.attempt
import io.github.matthewjones372.lark.fail.bind
import io.github.matthewjones372.lark.parZip

data class Missing(val id: Int)

fun user(id: Int): Either<Missing, String> = attempt { if (id == 1) "ada" else fail(Missing(id)) }

fun pair(a: Int, b: Int): Either<Missing, Pair<String, String>> = attempt {
    parZip({ user(a).bind() }, { user(b).bind() }) { x, y -> x to y }
}
```

## Where Lark differs

- **`catch (e: Exception)` cannot swallow a failure.** A `fail` leaves its block
  as a `Throwable` that is not an `Exception`, and `nonFatalOrThrow()` rethrows
  it. Arrow's `raise` is a `CancellationException`, which a broad `catch` and
  some libraries take for a cancellation.
- **A failure never crosses a thread.** Each fork fails into a scope of its own,
  and the combinator carries the failure back. A `fail` on a scope from a thread
  it did not fork, or after its `attempt` has returned, is refused with an error
  that names where the scope was opened.
- **No `Option`.** A nullable type says the same thing, and the compiler checks it.

## Using both

`lark-arrow` adds `arrow-core` and nothing else:

| Call | What it does |
|---|---|
| `either.toArrow()`, `either.toLark()` | an `Either` from one library to the other |
| `nel.toArrow()`, `nel.toLark()` | the same for `NonEmptyList` |
| `raise.lark { … }` | Lark code inside an Arrow `Raise`: a `fail` in it raises there |
| `arrow { … }` inside a `Fail` | Arrow code inside Lark: a `raise` in it fails there |

<!-- arrow-both -->
```kotlin
import arrow.core.raise.Raise
import arrow.core.raise.either
import io.github.matthewjones372.lark.arrow.arrow
import io.github.matthewjones372.lark.arrow.lark
import io.github.matthewjones372.lark.arrow.toArrow
import io.github.matthewjones372.lark.arrow.toLark
import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.Fail
import io.github.matthewjones372.lark.fail.attempt
import io.github.matthewjones372.lark.fail.fail
import io.github.matthewjones372.lark.parZip

data class NotFound(val id: Int)

// Moved to Lark: the function asks its caller for a Fail.
context(_: Fail<NotFound>)
fun user(id: Int): String = if (id > 0) "user-$id" else fail(NotFound(id))

// Not moved yet: still on Arrow's Raise.
fun Raise<NotFound>.orders(id: Int): List<String> = if (id > 0) listOf("order-1") else raise(NotFound(id))

// Lark calling Arrow: arrow { } fails the Lark scope with what the Arrow code raised.
fun dashboard(id: Int): Either<NotFound, Pair<String, List<String>>> = attempt {
    parZip({ user(id) }, { arrow { orders(id) } }) { user, orders -> user to orders }
}

// Arrow calling Lark: lark { } raises into the Arrow scope what the Lark code failed with.
fun name(id: Int): arrow.core.Either<NotFound, String> = either { lark { user(id) } }

// An answer handed across as a value, either way.
fun forArrow(id: Int): arrow.core.Either<NotFound, Pair<String, List<String>>> = dashboard(id).toArrow()

fun forLark(id: Int): Either<NotFound, String> = name(id).toLark()
```

That lets a service move one function at a time, with each side calling the
other through the bridge until nothing on the Arrow side is left.
