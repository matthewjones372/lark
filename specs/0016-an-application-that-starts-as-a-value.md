# 0016 — An application that starts as a value

## Problem

A service on lark wires itself by hand in `main`, so nested `resourceScope`
blocks *are* its dependency graph: moving a dependency means moving a brace,
release order is right by accident, and nothing starts in parallel because a
`resourceScope` body is one thread. A test that needs one repository starts the
whole tree, since the only way to reach a leaf is to build the trunk.

`Resource`, `parMap`, `Schedule` and `timeout` already do the work. What is
missing is a value saying which service needs which, to point them at.

## Not doing

- **No Pekko.** Actor nodes, `CoordinatedShutdown` order and `ask` readiness
  are `lark-pekko`'s, in their own spec.
- **No built-in services.** `Clock`, `Random`, `Sys` and their doubles are
  later; this spec has no opinion on what a node holds.
- **No ambient context.** `logInfo`, annotations and a test clock need lark to
  rebind context across a fork. Separate spec.
- **No config decoding and no reflection** beyond `typeOf<A>()`.
- **No annotation processor.** KSP models declarations, not bodies, and a graph
  assembled by `single { } + single { }` is an expression: there is nothing for
  it to read. Moving the graph into annotations to get ZIO's compile-time
  report would cost `plus`, `subgraph` and `render`, which is the whole spec.
  `validate` is the gate instead, and the report is what carries the weight.
- **No local environment.** ZIO's `provideSomeLayer` over one inner call, which
  is how its `Live` works, has no analogue: one graph, one instance per key.
  Two `DataSource`s are two keys.

## Shape

A module, `lark-app`, on `lark` and the Arrow that arrives with it.

```kotlin
interface Wiring : ResourceScope                        // install, and refuse to start

val persistence =
    single { cfg: DbConfig -> install({ HikariDataSource(cfg) }) { ds, _ -> ds.close() } } +
    single { ds: DataSource, log: Log -> PgUserRepo(ds, log) as UserRepo }

val app = core + persistence + web                      // right wins, so this is override

fun main() = runApp(app) { server: HttpServer -> server.start(); awaitShutdown() }
```

Dependencies are the recipe's parameters, not lookups in its body, so the graph
is data before a recipe runs:

```kotlin
app.validate()                    // Either<NonEmptyList<WiringError>, Plan>
app.render()                      // mermaid, golden-tested: an edge cannot appear unnoticed
app.subgraph<UserRepo>()          // DbConfig, DataSource, Log. No Kafka, no HTTP.

app.overriding(single<Clock> { FixedClock(t0) })   // raises if Clock is not already a key

testApp(app.subgraph<UserRepo>() + single<Clock> { FixedClock(t0) }) { repo: UserRepo ->
    repo.find(id).shouldBeRight()
}

single { cfg: DbConfig -> install({ HikariDataSource(cfg) }) { ds, _ -> ds.close() } }
    .probe("db", timeout = 5.seconds, retry = Schedule.recurs(5)) { it.isValid() }
```

## Why this shape

Declaring dependencies in the parameter list rather than fetching them from the
scope is the whole spec: it is what lets `validate`, `subgraph` and `render`
answer without running anything. The alternative, a `use<T>()` in the body,
reads slightly better and makes the graph opaque; recommended against, with an
escape hatch for the rare dynamic node.

`plus` is override, so it is also ZIO's `provideSome`: a module is already a
partial environment and `validate` says what is still missing, rather than a
type discharging it a layer at a time. What that costs is a typo — a fake under
a key nothing asked for is silently a new node, and the real one still runs. So
`overriding` is `plus` that raises unless every key it carries is already
there, and it is what a test should reach for.

Release order is reverse **topological**, not reverse acquisition: under
`parMap` acquisition order is nondeterministic, and `Resources` is written for a
single installing thread. So `lark-app` keeps its own guarded release list keyed
by node. One `resourceScope` for the whole graph is fewer lines and races on
install.

## Stack

- [ ] **`spec-0016-module`** — `Module`, `single` arities, `KType` keys, `plus`,
      `validate`, `render`, the module and its `NoOtherDependenciesTest`.
      Done when: a missing dependency and a cycle each come back as a `Left`
      naming the key, and no recipe body has run.
- [ ] **`spec-0016-start`** — `Wiring`, layered start under `parMap`,
      reverse-topological release, `use`.
      Done when: a node that refuses leaves nothing acquired, and two
      independent nodes report different virtual threads.
- [ ] **`spec-0016-run`** — `runApp`, `awaitShutdown`, exit codes, the signal
      handler.
      Done when: a SIGTERM releases the graph in order and leaves with 0.
- [ ] **`spec-0016-subgraph`** — `subgraph<A>()`, override precedence, `testApp`.
      Done when: a leaf's subgraph builds none of the unrelated nodes, and
      `testApp` releases after an assertion failure inside it.
- [ ] **`spec-0016-probe`** — `probe(name, timeout)`, readiness as the gate
      dependents wait on.
      Done when: a probe that never passes fails start naming its node, and a
      wedged probe fails inside its timeout rather than hanging.
- [ ] **`spec-0016-arities`** — `single` to nine dependencies, in `Single.kt`.
      Done when: a recipe takes nine parameters and the graph reads all nine.
- [ ] **`spec-0016-health`** — `critical`, `HealthRegistry`, readiness and
      liveness as something a route can be handed.
      Done when: a wedged probe answers Down inside its timeout, and one
      non-critical probe down does not make readiness Down.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **`typeOf<A>()` keys or a declared `Key<A>`?** Recommend `KType`: no ceremony,
   and `ActorRef<T>` stays distinct from `ActorRef<U>`, at the cost of erasure
   surprises on a type parameter.
2. **Does start stop at the first failure or accumulate?** Recommend first for
   start, since rollback wants one cause, and `parMapOrAccumulate` for
   `validate`, so every missing dependency is named at once.
3. **Is `probe` this stack's fourth entry or spec 0013?** Recommend splitting if
   this page needs to be shorter; it is the entry that makes "started" mean
   "ready" rather than "constructed".
4. **`AGENTS.md` names three modules, and its "Values, errors and effects"
   section describes an endpoint DSL absent from this repo (`routes`,
   `ServerEndpoint`, `handledOrFail` are in no source file). Fix before this
   stack, on its own?** Recommend yes.
