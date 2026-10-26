# 0136 — A choice made inside the graph

## Problem

A setting that picks between modules, such as an in-process bus or a Kafka
one, is read today by `Config.choosing`, before the graph exists. So the
application becomes a function of a `Config`:

```kotlin
fun petshopFrom(conf: Config): Module = configOf(conf) + … + bus(conf) + web
object Petshop : SurefoldApp<PelicanServer>() { override val module = petshopFrom(layeredConfig().config) }
```

The document is passed twice, tests rebuild the app from a hand-written
`Config` instead of overriding one key, `overridingConfig` cannot flip the
choice, and the compiler checker cannot read a graph built by a function, so
the editor goes quiet on it. The cookbook teaches this shape.

## Not doing

- No choice between graphs that disagree on what they provide to the rest. Every
  branch provides the same keys the graph asks of the choice.
- No re-choosing after start; the branch is fixed for the run.
- `Config.choosing` stays, for a choice that really is made before the graph.

## Shape

`choice` is a node that depends on the setting, and whose value is a module
surefold builds into the graph once that setting is built:

```kotlin
val bus: Module =
    config<BusSettings>("petshop.bus") { busSettings() } +
        choice(provides = listOf(typeOf<EventBus>())) { chosen: BusSettings ->
            when (chosen) {
                BusSettings.InProcess -> inProcessBus
                is BusSettings.OnKafka -> kafkaBus(chosen)
            }
        }

val petshop: Module = loadConfig() + infrastructure + shop + events + bus + web
```

- **Validation before running** treats the choice as a node that provides
  `provides`, so the rest of the graph is checked as today.
- **At start**, surefold builds the choice's dependencies, calls it, validates the
  branch against what it was declared to provide and what it needs, and builds
  it in dependency order with the rest. A branch that does not fit refuses the
  start, naming the branch.
- **Release** is the graph's: the branch's nodes are released in reverse order
  with everything else.
- **Tests** flip it with `overridingConfig("petshop.bus.kind = kafka")`, and
  `overriding` reaches a node the chosen branch provides.
- **`render` and `surefoldWiring`** draw the choice as one node providing its keys.

## Why this shape

The choice has to be a node because the setting is: anything else reads the
configuration a second time, outside the graph. Declaring `provides` is what
keeps validation before running: without it, nothing can be checked until the
branch exists. The alternative, one `EventBus` node whose recipe builds either
bus, works today but makes the branch's resources (two Kafka producers) a class
that closes them by hand rather than nodes the graph closes.

## Stack

- [ ] **`spec-0136-choice`**: `choice` in `surefold-app`, built and released as above,
      with tests for both branches, a branch that does not provide what it
      declared, and release order.
      Done when: `./gradlew :surefold-app:build` passes with the new tests.
- [ ] **`spec-0136-overriding`**: `overriding` and `overridingConfig` reach a
      chosen branch, with tests.
      Done when: a test flips a choice with `overridingConfig` and replaces a
      branch's node with `overriding`.
- [ ] **`spec-0136-checker-docs`**: the compiler checker reads `choice` as a
      node providing its keys, `render` draws it, and the cookbook's "A setting
      that picks a module" leads with `choice`.
      Done when: a TestKit graph with a `choice` compiles without
      `SUREFOLD_APP_UNREAD`, and `CookbookTest` compiles the recipe.

## Acceptance

```bash
./gradlew :surefold-app:build :surefold-app-gradle:test :surefold-app-pekko:test
```

## Open questions

1. **`provides` as `typeOf` values, or as a type parameter** (`choice<BusSettings,
   EventBus> { … }`)? Recommend the type parameter for one key, `provides` for
   more.
2. **Where `choice` lives:** `surefold-app`, since it is about the graph, not about
   HOCON. Recommend `surefold-app`.
3. **Does every branch get validated before running**, if surefold is handed them
   all (`choice(inProcess = …, onKafka = …)`)? Recommend: not in this spec; a
   branch built from the setting's values cannot be listed in advance.
