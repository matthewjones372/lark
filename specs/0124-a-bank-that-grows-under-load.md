# 0124 — A bank that grows under load

## Problem

lark-bank (0094) is always three nodes. `Main.kt` hard-codes their names,
ports and `127.0.0.1` seeds, and `--node` accepts only n1, n2 or n3. Nothing
deploys it, so nobody can watch it do what the cluster was built for: add
members as load rises, and lose them again without losing a transfer. Specs
0080, 0090, 0096 and 0099 each name an autoscaled pod as their reason, and
the bank uses none of them. It leaves with `leaveWithin = Duration.ZERO`, so
every stop looks like a crash. It shards without `rebalance`. It downs by
`keepMajority`, which an even member count can split.

## Not doing

- **Scaling on anything but CPU.** No Prometheus, no custom metrics, no
  KEDA. 0094's "not doing" for exporters stands. Scaling on transfers a
  second is a spec of its own.
- **Other platforms.** No ECS, Nomad or compose-based autoscaling, though
  0096's AWS discovery would serve.
- **Scaling the database.** One Postgres. Journal slices (0105) stay out.
- **Production manifests.** No Helm chart, ingress, TLS or secrets
  management. The manifests are for kind or minikube.
- **Changing local runs.** `./gradlew :lark-bank:run` still starts three
  nodes in one JVM.

## Shape

```bash
kubectl apply -k lark-bank/deploy   # Postgres, 3 bank pods, an HPA up to 10
```

- **Named by the pod.** With `POD_NAME` set, a node takes its name from it,
  binds `POD_IP`, and joins through `Kubernetes.joining(selector, port,
  lease)`, which gives the seeds and the `Lease` that breaks even splits.
  Without it, `Main` behaves as today.
- **Leaves, not crashes.** In a pod, `leaveWithin` is 20 s. On SIGTERM the
  HTTP server stops taking requests first. Then the durable producers drain
  and the node leaves. `terminationGracePeriodSeconds` is 60.
- **Shards follow.** Both kinds shard with `Rebalance.byLoad()`, so a new
  pod takes busy shards instead of waiting for hash churn.
- **Probes.** `/health/live` answers while the JVM is up. `/health/ready`
  answers when this node is `Up` and its journal answers. It is not
  `cluster.ready()`, which turns every pod unready while any one member is
  unreachable.
- **Deploy files.** `lark-bank/deploy/` holds:
  - a Dockerfile from `installDist`;
  - a StatefulSet with CPU requests;
  - a headless Service plus an HTTP Service;
  - an HPA (min 3, max 10, 70% CPU, 5-minute scale-down window);
  - a PodDisruptionBudget (`maxUnavailable: 1`);
  - RBAC for pods and Leases;
  - a single-pod Postgres;
  - a kustomization over all of them.

## Why this shape

Every piece already exists in lark. The bank only has to use it, so this is
wiring, not library work. CPU is the one metric an HPA reads with nothing
installed beside it. The bank's load is CPU-bound, so CPU follows transfers
a second closely enough for a demo. A StatefulSet gives stable names, so a
restarted pod comes back as itself (0097). A Deployment would also work,
since 0099 resumes a gone life's commands, but it churns names on every
roll. Recommended: the StatefulSet.

## Stack

- [ ] **`spec-0124-pod`** — `Main` takes name, host and seeds from the pod
      env, and adds `lark-cluster-kubernetes` (with `NoOtherDependenciesTest`
      updated). Done when: a test with `POD_NAME` set and a stub discovery
      joins a node by that name, and the no-env run is unchanged.
- [ ] **`spec-0124-leave`** — `leaveWithin` from settings, the server closed
      first, and the Lease downing in pods. Done when: a node closed with
      transfers in flight is `Removed`, never `Downed`, and none are lost.
- [ ] **`spec-0124-probes`** — `/health/live` and `/health/ready`. Done when:
      ready stays true on n1 while n2 is unreachable, and false before n1
      is `Up`.
- [ ] **`spec-0124-rebalance`** — `Rebalance.byLoad()` on both kinds. Done
      when: a fourth node joining a loaded three owns shards within two
      intervals.
- [ ] **`spec-0124-deploy`** — `lark-bank/deploy/` and a README section.
      Done when: on kind, load from the admin page takes the HPA past three
      pods, and stopping it returns to three with no member `Downed`.

## Acceptance

```bash
./gradlew :lark-bank:test
kind create cluster && kubectl apply -k lark-bank/deploy
kubectl get hpa lark-bank -w        # replicas rise past 3 under load
```

## Open questions

1. **StatefulSet or Deployment?** Recommended: StatefulSet, as above.
2. **Minimum replicas?** Recommended: 3. One pod can still serve, but three
   shows sharding and a crash from the start.
3. **Postgres connections.** Each node opens a pool of 10, so ten pods need
   100. Recommended: a pool size from `DB_POOL`, default 10, set to 5 in the
   manifests.
4. **Who starts load in a pod?** The generator is per node and started from
   its own page. Recommended: `/api/load` on the HTTP Service turns it on
   for every node through the `bank-events` topic, so one click loads the
   whole cluster.
