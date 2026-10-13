# 0041 — A fork mistake the compiler sees

## Problem

lark's structure rules are written down but only partly enforced, and all of
the enforcement happens at runtime:
- a `Deferred` is awaited and cancelled only by the thread that opened it
  (0035);
- no fork outlives its block (0002);
- a `Raise` never crosses a thread (AGENTS.md).

Each rule has a spelling that compiles and then fails somewhere else:

```kotlin
flock {
    val a = async { load() }
    val b = async { a.await() + 1 }    // a's raise surfaces into the outer scope, from b's thread
    b
}                                      // a Deferred returned past the scope that joins it

either outer@{ flock { async { this@outer.raise(Gone) } } }   // a raise on the wrong thread

async { try { rows.bind() } catch (e: Exception) { empty } }  // eats the raise, and the interrupt
```

`StructuredTaskScope` (0040) catches the nesting ones with
`StructureViolationException`, at runtime. Detekt catches the last one in this
repo only, and a consumer's build never runs it.

## Not doing

- **No data-flow analysis.** Each check reads one call and the lambdas nested
  in it. A `Deferred` passed through three functions and awaited elsewhere is
  not caught.
- **No IR and no code generation.** Checkers only, as in `lark-app-compiler`,
  so the editor runs them with nothing installed (0027).
- **No check that needs the runtime.** Whether a fork actually blocks, or on
  which executor, is 0042's job.

## Shape

A new module, `lark-compiler`: FIR checkers, no IR. A Gradle plugin applies it.

| Diagnostic | Severity | Fires on |
|---|---|---|
| `DEFERRED_ESCAPES_SCOPE` | error | a `Deferred`, `Channel` or `Flock` returned from `flock`/`structured`, or stored in a property |
| `AWAIT_ACROSS_FORK` | error | `await`/`cancel` on a `Deferred` declared outside the `async` lambda it is called in |
| `RAISE_ACROSS_FORK` | error | a labelled `this@x` of a `Raise` receiver used inside an `async` lambda that `x` does not own |
| `CATCH_EATS_RAISE` | warning | a `catch` of `Throwable`, `Exception`, `RuntimeException` or `InterruptedException`, around `bind`/`raise`/`await`, that neither rethrows nor re-sets the interrupt flag |

Each message names the rule and the fix, for example: "await `a` in the
scope's own block and pass the value in, or open `b` after `a.await()`".

## Why this shape

Every check reads a type (`Deferred`, `Raise`) or a lambda boundary (`async`),
and both are local to one call in FIR. That makes them sound for what they
claim, and cheap enough to run on every keystroke. Rules that need data flow
are left out rather than approximated, because a check that misfires teaches
people to ignore it.

No Java or Scala library can do this. The JDK's own structured concurrency
reports these mistakes as exceptions when the code runs.

The alternative is to put the checks into `lark-app-compiler`. It is already
loaded in the editor, but it would tie structured-concurrency checks to the
application DSL. Recommended: a separate module, sharing `Versions.kt` for the
compiler-version guard.

## Stack

- [ ] **`spec-0041-module`** — `lark-compiler`, its Gradle plugin, `DEFERRED_ESCAPES_SCOPE`.
      Done when: a compile test shows the error on a returned `Deferred` and
      none on a returned `await()` value.
- [ ] **`spec-0041-threads`** — `AWAIT_ACROSS_FORK` and `RAISE_ACROSS_FORK`.
      Done when: both examples above fail to compile, and awaiting in the
      scope's own block still compiles.
- [ ] **`spec-0041-catch`** — `CATCH_EATS_RAISE`.
      Done when: the example warns, a `catch` that rethrows doesn't, and
      `catch (e: IOException)` doesn't.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Is `AWAIT_ACROSS_FORK` an error even though it sometimes works?** A body
   that never raises runs correctly today. Recommended: error. It is one refactor
   away from a raise on the wrong thread.
2. **Does `async` passing a `Deferred` into a fork as a parameter count as an
   escape?** Recommended: no, since the type tells you nothing. That is the
   data-flow case left out above.
3. **Which Gradle plugin applies it?** Recommended: a new plugin ID, so a
   service without the app DSL can take it alone.
4. **Warning or error for `CATCH_EATS_RAISE`?** Recommended: warning, because a
   catch-all at a process edge is legitimate. `allWarningsAsErrors` makes it an
   error in this repo anyway.
