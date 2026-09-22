# 0040 — A flock the JDK can see

## Problem

To the JDK, a lark fork is a loose virtual thread. `jcmd <pid>
Thread.dump_to_file -format=json` lists it with no parent, so an operator
looking at a stuck service cannot tell which handler's `flock` a thread belongs
to. `LarkLocal` reaches a fork only because `Fork` copies a `ThreadLocal`
snapshot across by hand (`Bindings.snapshot()`). And "no fork outlives its
block" is a promise only lark's own `close` keeps.

JDK 27 ships `StructuredTaskScope` as a seventh preview (JEP 533), and JEP 543,
a candidate, proposes it final in JDK 28 "without further change". It gives:
- the scope tree in thread dumps;
- `ScopedValue` inheritance into subtasks;
- `StructureViolationException` for scopes nested out of order;
- a timeout that needs no sleeper thread.

On its own it is worse than lark. Failures arrive wrapped in
`ExecutionException`, and there are no typed errors, no per-subtask cancel, no
lazy fork, and forks only through a `ThreadFactory`.

## Not doing

- **No change to `lark`.** This is a new module, `lark-structured`, depending on
  `lark` and the JDK only. `flock` stays as it is for anyone below 27.
- **No `on: Executor`.** A scope forks through a `ThreadFactory`, so a Pekko
  dispatcher (0004) can't be the executor here. Use `flock` for that.
- **No publishing while the API is a preview.** It builds and is tested on 27,
  and is published once JDK 28 makes the API final.

## Shape

```kotlin
structured<Err, Dashboard>(name = "dashboard", timeout = 2.seconds) {   // Either<Err, Dashboard>
    val user   = async { users.find(id).bind() }
    val orders = async(start = Start.Lazy) { orders.forUser(id).bind() }
    Dashboard(user.await(), if (wantsOrders) orders.await() else emptyList())
}
```

- The receiver is the same `Flock<E>`, so any body written for `flock` runs
  unchanged under `structured`.
- The scope is `StructuredTaskScope.open(joiner, config)`. A fork is a
  `fork(Callable)` whose body captures its `Outcome` as a value, so the JDK only
  ever sees a subtask that succeeded, and no `ExecutionException` is unwrapped.
- A lark `Joiner` holds lark's policy. `flock` answers with the first unnoticed
  failure at close; `parZip` and `raceN` cancel the scope from `onComplete`.
- A lazy fork calls `fork` on its first `await`, from the owner thread, which
  is the only thread lark lets await.
- `Deferred.cancel()` interrupts the one subtask's thread through 0037's states.
  The JDK has no per-subtask cancel, so lark supplies it.
- `LarkLocal` is backed by a `ScopedValue` inside `structured`, so subtasks
  inherit it without a copy.

## Why this shape

Neither lark alone nor `StructuredTaskScope` alone does the job. The JDK
maintains the threads, the tree, the inheritance and the structure checks, and
lark adds the typed errors, laziness and per-fork cancel the JDK leaves out.
Keeping the same `Flock` receiver means `structured` is a different
implementation of lark's scope, not a second model.

The alternative is to swap `flock` itself onto `StructuredTaskScope` when the
runtime is 28 or later. It is invisible to users, but it gives `lark` two
implementations chosen at runtime, and it loses `on:`. Recommended: a module
the user opts into.

## Stack

- [x] **`spec-0040-spike`** ([#86](https://github.com/matthewjones372/lark/pull/86)) — the module, toolchain 27, `--enable-preview` for its tests,
      a CI job on 27.
      Done when: a scope opened from Kotlin shows two named subtasks under their
      parent in a JSON thread dump.
- [x] **`spec-0040-flock`** ([#86](https://github.com/matthewjones372/lark/pull/86), prototype) — `structured`, `async` (eager and lazy), `await`, `cancel`.
      Done when: `FlockTest` and `LarkLocalTest` run against `structured` as
      well as `flock`, and pass with only the scope factory changed.
      Built instead: the module's own suite of 22 tests. The shared suites
      are still to do.
- [ ] **`spec-0040-locals`** — `LarkLocal` on `ScopedValue` inside `structured`.
      Done when: a value bound before `structured` is read in a fork of a fork
      with no `Bindings` snapshot taken.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `kotlinc` enforce preview APIs?** `javac` does. If Kotlin does not,
   class files built on 27 may load on 28 unchanged. The spike answers this.
2. **Build now on 27, or wait for 28?** Recommended: build on 27 behind the
   spike, and publish only on 28.
3. **`LarkLocal.attach` inside `structured`?** A scoped value binds for a block
   only. Recommended: `attach` keeps the `ThreadLocal` path and says so.
4. **`timeout` through `Configuration.withTimeout`?** It ends the whole scope,
   not one block. Recommended: yes for `structured(timeout = …)`, and keep
   `timeout { }` as it is.

## Found while building (2026-09-22)

The prototype is `lark-structured` on Temurin 27+35. It also has `parAll` and
`firstOf`, each a JDK `Joiner`.

- **Open question 1 is answered: Kotlin does not enforce the preview.** `kotlinc`
  compiles the calls with no flag, and writes class file 70.0 with no preview
  marker. The JDK doesn't check at runtime either, so the module and its tests
  run without `--enable-preview`.
- **Kotlin 2.4 targets JDK 26 at most.** The module compiles against 27's class
  library and emits 26 bytecode.
- **A subtask's thread is never reused.** So a late `cancel()` interrupt can't
  land on another task, and 0037's `Interrupting` state isn't needed here.
- **A fork started after a deadline never runs.** `fork` on a cancelled scope
  starts no thread. The module's own `ThreadFactory` records the thread, so a
  lazy fork awaited after the deadline answers with it instead of hanging. The
  factory also names each fork's thread after its scope.
- **`await` from another fork is refused.** It throws `WrongThreadException` at
  runtime, which is 0041's `AWAIT_ACROSS_FORK` enforced without the compiler.
- **Deliberate difference from `flock`:** a fork the close cuts short does not
  fail the scope. `flock` answers with the interrupt instead (0035).

