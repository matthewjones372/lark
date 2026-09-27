# 0096 — a cluster joined from config

## Problem

Joining a cluster on Kubernetes takes an application about sixty lines of
plumbing that has nothing to do with the application. lark-bank's
`app/.../Cluster.kt` is the example:

- It builds fabric8's `KubernetesClientBuilder().build()` itself, so it imports
  a Kubernetes client that `lark-cluster-kubernetes` already brings. It then
  threads that client into `Kubernetes.discovery` and `Kubernetes.lease`, and
  closes it when the app is released.
- It splits `"host:port"` seed strings into `Node("", host, port)` by hand for
  `Discovery.static`.
- It switches on a `discovery = "static" | "kubernetes"` string to choose the
  discovery and the downing that go together: a lease on Kubernetes, and
  keep-majority otherwise.
- It reads its own `ClusterSettings` (seeds, namespace, selector, lease,
  `stableAfter`, `formAfter`, `probeEvery`, `ackWithin`) and copies them into
  `Gossiping` and `Downing`.
- It writes `Cluster.up()`, meaning "self is Up in its own view", for the
  readiness probe.
- It subscribes an actor that calls `exitProcess(1)` when this node is downed,
  so the orchestrator starts a fresh process.

Every clustered application will write the same lines, and each will get the
details slightly different: who closes the client, which namespace, and what
readiness means. The bank did not choose any of this. Lark should own it.

## Not doing

- **Node incarnations.** A node restarted on the same address after it was
  downed can be downed again as its old self. That is a membership bug, and it
  gets a spec of its own. This spec keeps today's exit-when-downed behaviour as
  a setting, and changes nothing about it.
- **A plugin registry for arbitrary discovery.** The backends are the ones Lark
  already ships: static, dns, srv, kubernetes, and aws's ecs and cloudmap. An
  application with its own `Discovery` still calls `cluster(...)` directly.
- **Changing `Flock.cluster(...)`.** The typed call stays as it is. This spec
  adds a layer above it.
- **Config for sharding, singletons or topics.** Only joining.

## Shape

Three parts, from the bottom up.

**1. `lark-cluster`: the pieces an application writes today.**

```kotlin
Node.at("10.0.0.7:25520")                        // host:port → Node("", host, port); a bad string is refused
Discovery.static("bank-1:25520", "bank-2:25520")
cluster.ready()                                  // already there (spec 0081): what readiness means
```

**2. A backend owns its client.** Each backend module gets one call that builds
the discovery, the downing that suits it, and the client they share, as a
`Joining` that closes what it opened:

```kotlin
// lark-cluster
class Joining(val discovery: Discovery, val downing: Downing, private val release: () -> Unit = {}) : AutoCloseable

// lark-cluster-kubernetes: the client from the pod's service account, the namespace the pod's own
Kubernetes.joining(selector = mapOf("app" to "lark-bank"), port = 25520, lease = "lark-bank-split-brain")

// lark-cluster-aws, alike
Aws.ecs(cluster = "bank", service = "bank", port = 25520, lease = "bank-split-brain")
```

The existing `Kubernetes.discovery(client, …)` and `Kubernetes.lease(client, …)`
stay for applications that bring their own client.

**3. `lark-app-cluster`: a cluster as a module, read from a HOCON section.**
The module builds on `lark-app-actor`, `lark-app-typesafe` and `lark-cluster`.
A backend is chosen by name, and its module is found on the classpath with a
`ServiceLoader`, so `lark-app-cluster` does not depend on fabric8 or the AWS
SDK:

```hocon
lark.cluster {
  node { name = ${?POD_NAME}, host = ${?POD_IP}, port = 25520 }
  join = kubernetes                        # static | dns | srv | kubernetes | ecs | cloudmap
  static.seeds = ["127.0.0.1:25520"]
  kubernetes { selector { app = lark-bank }, lease = lark-bank-split-brain }   # namespace: the pod's own
  downing.stableAfter = 20s                # lease when the backend has one, keep-majority otherwise
  gossip { probeEvery = 1s, ackWithin = 600ms, formAfter = 5s }
  leaveWithin = 30s
  roles = []
  whenDowned = exit                        # exit | stay
}
```

```kotlin
// lark-bank, after: Cluster.kt's seeding, ClusterSettings and membership exit go
val app = loadedConfig() + actors() + cluster("bank.cluster") + persistence + entities
// cluster(...) provides Cluster, releases its Joining after the flock leaves,
// and probes "cluster" readiness on cluster.ready()
```

A `join` naming a backend that is not on the classpath is refused at start, and
the refusal names the module to add, for example "join = kubernetes needs
lark-cluster-kubernetes". The refusal uses the same config faults as every
other section, all of them reported at once.

## Why this shape

The bank's complaint is not about any one call. It is that the application has
to know how a backend is put together. Part 2 fixes that for anyone using the
typed API. Part 3 makes the everyday case, where the backend is chosen by the
environment, a matter of config. Without Part 3, each application would still
write the `when (discovery)` switch. The alternative is one
`lark-cluster-config` module that depends on every backend. That would force
fabric8 and the AWS SDK onto an application that uses neither, and every
module's `NoOtherDependenciesTest` exists to stop exactly that. So the
recommendation is `ServiceLoader`, with each backend registering its own name.
It is the only reflection in the design, and it happens once, at start.

## Stack

- [x] **`spec-0096-seeds`**: `Node.at`, `Discovery.static("host:port", …)`, and `Joining` with `Joins` found by name.
      Done when: a test parses good and bad seed strings, and `static`, `dns` and `srv` are found by name.
- [x] **`spec-0096-joining`**: `Joining`; `Kubernetes.joining` builds and closes its own client, with the pod's namespace as default.
      Done when: against the mock API server, `joining(...).use { }` finds pods, takes the lease, and closes the client.
- [x] **`spec-0096-aws`**: `Aws.ecs(…, lease)` / `Aws.cloudMap(…, lease)` as `Joining`, with the DynamoDB lease.
      Done when: the existing AWS tests pass through `Joining`.
- [x] **`spec-0096-app`**: `lark-app-cluster`: the `cluster(path)` module, the `ServiceLoader` backends, the readiness probe, and `whenDowned`.
      Done when: three nodes join from `configOf(...)` with `join = static`; a missing backend is refused with the module's name; and the flock leaves before the `Joining` is closed.
- [x] **`spec-0096-guide`**: the cluster guide's "on Kubernetes" section rewritten around the config.
      Done when: the guide's HOCON reads without a fault in `GuideConfigTest`, and joins through its seeds.

## Acceptance

```bash
./gradlew build
./gradlew :lark-app-cluster:test :lark-cluster-kubernetes:test :lark-cluster-aws:test
```

In lark-bank, `Cluster.kt` loses `seeding`, `Seeding`, `ClusterSettings` and
the exit in `membership`. `app/build.gradle.kts` no longer compiles against
fabric8. The bank's kind deploy joins with only env changes.

## Open questions

Each recommendation was taken. Settled while building:

- **`Cluster.isUp` was not added.** `cluster.ready()` already exists (spec 0081), and it is stricter: `Up`, with a
  leader, and every member reachable. The module's probe asks it.
- **`Discovery.static` of strings takes at least one.** `static(first, vararg rest)`, because a second
  `vararg` overload would make `Discovery.static()` ambiguous.
- **The `Joins` interface lives in `lark-cluster`,** with `static`, `dns` and `srv` registered there, and each backend
  module registers its own. A backend is handed `JoinOptions`: this node's port, `stableAfter`, and its own section as
  plain values, so `lark-cluster` reads no config format. A value it needs and was not given is refused with its path.
- **`whenDowned = exit` exits on a platform thread,** since the exit runs the shutdown hooks that close the flock the
  actor is stepping in; the process then leaves through `runApp`'s own release.

1. **Should `cluster(path)` read HOCON, or take a typed `ClusterSettings`?**
   Recommended: both. `cluster(settings)` is the typed module, and
   `cluster(path)` reads a section into it. A test or a non-HOCON application
   then needs no config file.
2. **Should `whenDowned` default to `exit` or to `stay`?**
   Recommended: `exit`. The process keeps running once its cluster actor has
   stopped, which is never useful behind an orchestrator. `stay` remains for
   tests.
3. **Where does the namespace default come from on Kubernetes?**
   Recommended: the service account's `namespace` file, then `POD_NAMESPACE`,
   and then refuse to start. Falling back to `default` silently is how two
   environments end up sharing a lease.
4. **Is `whenDowned = exit` worth shipping before incarnations?**
   Recommended: yes. It is what applications do by hand today, and the
   incarnations spec changes what a downed node does next, not whether it
   exits.
