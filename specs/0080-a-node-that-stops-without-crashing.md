# 0080 — A node that stops without crashing

## Problem

A node that closes its flock simply stops answering. Nothing in lark calls
`Cluster.leave()` on the way out, `lark-app-actor` included, so to the rest of
the cluster every deploy, scale-down and restart looks like a crash. The
others wait for the probes to fail, then for `stableAfter` (20 s by default)
before downing the node, then as long again before removing it. For that whole
time the node's shards have no owner, and every message sent to them is kept
or dropped. 0079's test showed the difference: a node that leaves hands its
shards over and loses nothing, and one that crashes loses what was in flight
and takes seconds to be replaced. What a service does today is call `leave()`
itself, and guess how long to wait before closing.

## Not doing

- **Pekko's `CoordinatedShutdown` phases.** No named phases: close hooks run
  in reverse order of registration, as `resourceScope` releases do, and that
  is all the ordering there is.
- **Handing over a singleton's state.** A singleton (0070) moves as it does
  now: it stops, and starts on its next owner.
- **Signals.** Catching SIGTERM is the application's: `lark-app` already turns
  it into a release of its nodes, and that release is what this hooks into.
- **Waiting for mailboxes to empty.** An entity's messages that arrive during
  the handover are the region's to keep or forward, as in a move today.

## Shape

```kotlin
flock<Nothing, Unit> {
    val cluster = cluster(node("shop-1", 25520), seeds)   // leaves by itself when the flock closes
    val payments = orders.reliable("checkout")
    …
}                                                       // leave, hand over, drain, then stop the actors

cluster.stop(within = 30.seconds)                       // or explicitly, e.g. before a service's own step
```

- **On close.** A flock can run hooks when it starts to close, before it stops
  any actor. `cluster(…)` registers one: leave, then wait up to `leaveWithin`
  (30 s by default, a `Cluster` setting) until this node is `Removed` from its
  own view, then let the flock stop the actors.
- **Handover.** While the node is `Leaving`, its regions already release
  their shards to the new owners (0070); the wait covers that, since removal
  follows every member having seen the node leave.
- **Producers.** A producer on the closing flock (0079) drains first, up to
  the same deadline, so its unconfirmed commands reach their entities before
  the node that resends them goes.
- **Timing out.** A leave that has not finished by the deadline is logged and
  the flock closes anyway: the others then treat the node as crashed, which is
  where every service is today.
- **`lark-app-actor`.** Its `Actors` node closes the flock this way, so an
  application that releases its nodes on SIGTERM leaves the cluster first.

## Why this shape

Leaving on close makes the good path the default one: a service that never
thinks about shutdown still hands its shards over on every deploy. The hook
runs before any actor stops, because the leave needs the membership actor and
the handover needs the regions. The alternative is to leave it all to an
explicit `cluster.stop()`, which is simpler and keeps flock close meaning
"stop now", but then every service must remember to call it, and those that
forget crash on every deploy. Recommended: on close, with `stop()` for a
service that wants to do it earlier.

## Stack

- [x] **`spec-0080-closing`** — `onClose` hooks on a flock, in `lark`'s
      `Flock`, run in reverse order of registration before any fork is
      interrupted or actor stopped. Done when: a hook that tells an actor and
      waits for its answer gets it, and a hook that throws is logged and the
      flock still closes.
      ([#211](https://github.com/matthewjones372/lark/pull/211))
- [x] **`spec-0080-leave`** — `Cluster.stop(within)` and the close hook in
      `cluster(…)`. Done when: of three nodes, one whose flock closes is
      removed from the others' views within `stableAfter`, rather than after
      being downed; and a node that cannot finish leaving closes by its
      deadline.
      ([#212](https://github.com/matthewjones372/lark/pull/212))
- [ ] **`spec-0080-drain`** — producers drain on close. Done when: 0079's
      test, with the third node's flock closed rather than crashed, applies
      every payment once with the producer's resend turned off.
- [ ] **`spec-0080-app`** — `lark-app-actor` closes through the hooks. Done
      when: an application released while its node is in a cluster leaves it
      before its flock is gone.

## Acceptance

```bash
./gradlew build
```

## Open questions

- **Leave on close by default, or only by an explicit `stop()`?**
  Recommended: on close, as above.
- **One deadline for leaving and draining, or one each?** Recommended: one,
  `leaveWithin`, since what a platform gives a pod to stop is one number.
- **Should a producer on a node that is not leaving a cluster drain on close
  too?** Recommended: yes; a flock's close is the one moment its unconfirmed
  commands are about to be lost, cluster or not.
- **Hooks on every flock, or only on a cluster's?** Recommended: every flock,
  as `onClose` in `lark-actor`; the cluster is one user of it, and a service
  gets the same place for its own last step.

Decided (2026-09-27): every open question goes as recommended. A cluster
leaves on its flock's close, with `stop()` for a service that wants to leave
earlier; one deadline, `leaveWithin`, covers leaving and draining; a producer
drains on close whether or not its flock is in a cluster; and close hooks are
`onClose` on every flock, in `lark-actor`.


Decided while building `spec-0080-closing`: the hooks live on `Flock` in
`lark` itself, not in `lark-actor`. A flock interrupts every fork at once as
it closes, and a remote node's transport runs in forks of the same flock as
the actors, so a hook in `lark-actor`'s guardian would find the transport
already interrupted and could never tell the cluster it is leaving. A hook
runs on the closing thread before any interrupt, so every fork, actor and
timer is still running for it. Running the hooks after the interrupt, or in
registration order, fails the tests.

Decided while building `spec-0080-leave`: `stop(within)` asks to leave and
waits until this node is no longer a live member of its own view, or is the
only member. `cluster(…)` takes `leaveWithin`, and a `leaveWithin` of zero
registers no hook, so the node goes as a crashed one does: the tests that are
about a crash say so, since the five-node test's own cleanup otherwise waited
on leaves that a crashed member held up, for 54 of its 60 seconds. A node that
cannot finish leaving, with the others unreachable and not yet downed, closes
after its deadline and a logged warning. Without the hook, a closed node is
seen unreachable rather than `Leaving`, and the test that it is removed
without being downed fails.
