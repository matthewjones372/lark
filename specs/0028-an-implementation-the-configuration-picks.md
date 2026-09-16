# 0028 — An implementation the configuration picks

## Problem

Spec 0023 made a HOCON section a node, which is right for a setting a recipe
*reads* and wrong for one that decides *which recipe exists*. `useCache = true`
does not configure a repository; it chooses between two.

Reading the raw path a second time at assembly — `conf.getBoolean("repo.useCache")`
beside a typed `config("repo") { … }` — puts one setting in two places, and the
raw read has none of `Reading`'s fault accumulation. Depending on both and
picking at start — `single { c: RepoConf, a: Cached, b: Plain -> … }` — builds
both: both `install` their resource, both are probed, both are released.

0023 ruled out a `choose` API because `Module` is a value and `when` over a
setting already answers with one. That is still true. What it missed is that
after `config()` the setting is *inside* the graph, so at the point `+` is
written there is nothing to `when` over.

## Not doing

- **No runtime branch.** A module spliced in after the graph starts is one
  `validate()`, `render()` and `checkWiring` cannot see. The choice is made
  before anything is built, or not at all.
- **No laziness.** A layer's nodes are all built; an unchosen branch is absent
  from the graph rather than skipped in it.
- **No profiles.** `dev`/`prod` as a named concept is a second config model
  beside HOCON's own, which already merges files.
- **No change to `config()`.** A section a recipe reads stays a node.

## Shape

In `lark-app-typesafe`. The section is read once, at assembly, and is still a
node afterwards:

```kotlin
fun persistence(conf: Config): Module =
    conf.choosing("repo", { RepoConf(boolean("useCache"), duration("ttl")) }) { repo ->
        if (repo.useCache) cachingRepo else plainRepo
    }

object Shop : LarkApp<Server>() {
    override val module = shop(ConfigFactory.load())
}

fun shop(conf: Config): Module = configOf(conf) + persistence(conf) + web
```

`RepoConf` is a node under its own key exactly as `config("repo")` would leave
it, so a recipe that wants `ttl` takes it as a dependency and nothing is read
twice. A section that will not read answers with `Reading`'s discarded values,
picks the branch those name, and refuses the start on the node itself — the
same `Refused` message `config()` gives, naming the file and the line.

## Why this shape

Assembly-time is the only time the choice can be made and still be a static
graph: `checkWiring` runs on `main.runtimeClasspath`, so it reads the service's
own `application.conf` and draws the branch that file picks. The cost is that a
branch selected only by a deployment override is not the one drawn, and that
`overridingConfig` in a test cannot un-pick a choice already made — a test
flips it by assembling with its own `Config`, or by `overriding` the node.

The alternative shape is `conf.choosing("repo") { … } into { … }`, two calls to
avoid a non-trailing lambda. Recommend the two-lambda call: `into` reads as a
combinator and this is an argument.

## Stack

- [ ] **`spec-0028-choosing`** — `choosing`, a `configOf(Config)` overload, and
      the `docs/app.md` section.
      Done when: a graph whose branch `application.conf` picks renders only
      that branch, and a missing key in the chosen section refuses the start
      naming the file and the line.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should the check draw both branches?** Recommend no: a diagram of a graph
   that never exists is worse than a diagram of the one `application.conf` runs.
2. **Should `overridingConfig` refuse where a choice was already made?** It
   cannot tell. Recommend documenting it under the testing section instead.
3. **Does a `Config`-free `Module.choosing(flag: Boolean)` belong in
   `lark-app`?** Recommend no: that is `if`, and 0023's objection stands.
4. **Is `RepoConf` still a node when only the choice reads it?** Recommend yes,
   always — an overload that drops it is an API for saving one key.
