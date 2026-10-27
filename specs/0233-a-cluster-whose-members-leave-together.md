# 0233 — A cluster whose members leave together

## Problem

When every member of a cluster leaves at once, as a test's nodes do when its scope closes, one of them often waits
about 20 seconds to be out. It does not leave early. It waits for keepMajority's `stableAfter` to down the peers that
have already gone.

In surefold's CI (about 100 builds, 2026-10-26), six first attempts failed on a cluster test. That is about one build
in 16. Each failure was the 60 s per-test limit, "no answer within 1m", in a test that normally takes a few seconds:

- `ShardingSuite` "a node joining and one leaving…": 3.3–3.5 s normally.
- `RememberedSuite` "a sharded persistent entity answers…": 2.3–2.8 s normally.
- `ClusteredSuite` "three nodes join from config alone…": about 23 s normally, 2.5 s once.

Suite totals stayed at 240–295 s, so the runners were not overloaded. The 23 s is mostly a test waiting on its own
nodes to leave. When that wait stacks on a slow start, the test passes 60 s.

On an idle machine, `ClusteredSuite`'s join test starts its three nodes in 2.3 s. When the nodes release, two are out
in about 10 ms. In about half the runs, the third takes about 21 s. Its log shows it probing the other two (dead
letters) and never seeing them `Removed`. It is out at 23.1 s, which is `stableAfter` plus a probe round.

The cause is in `Membership` and `Cluster.stop`:

- A member is out (`View.outFor`) once no other member is `Up`. The member that sees that first stops at once. It
  tells nobody, and nobody probes it to find out.
- A member still running may hold an older view, where the one that stopped is `Up`, or is `Leaving` and not yet
  `Removed`. To that member, the one that stopped is now just unreachable. While anyone is unreachable, gossip has not
  converged and the leader moves nobody on (`lead` needs `converged()`). Only `decide` can clear it, and `decide`
  waits `stableAfter` (20 s for `keepMajority`'s default) before downing.

## Not doing

- **No longer test timeouts.** The timeout is right; the leave is slow.
- **No change to `stableAfter` or any downing strategy's defaults.** A crashed member still waits `stableAfter`.
- **No change to how a member that crashes, rather than leaves, is handled.**
- **Not `RebalanceSuite`'s "at most three rounds", or bank's `AdminPageSuite` "n1 never saw all three nodes up"
  (`CrashSuite.kt:43`).** Neither is a timeout. Both are rechecked once this lands, and get their own issue if they
  still fail.

## Shape

A member that has left says so before it stops. Its peers then remove a leaving member that has gone quiet, without
waiting to down it:

```kotlin
// MembershipSuite: three Up members leave in the same tick, and each is out within a few probe rounds.
val net = Net()
val three = listOf(1, 2, 3).map { net.start(it, seeds(1, 2, 3)) }
net.until { listOf(1, 2, 3).all { net.view(it).up().size == 3 } } shouldBe true
three.forEach { it.leave() }
net.until(steps = 50) {
    listOf(1, 2, 3).onEach { if (net.view(it).outFor(at(it))) net.stop(it) }.all(net::stopped)
} shouldBe true // 5 s of simulated time, well short of the 20 s stableAfter
```

- **A last word.** `Membership.farewell()` returns a `Ping` carrying this member's gossip to every other live member,
  as `lead` already does for a member it removes. `Cluster.stop` sends it once `outFor` holds, before the scope stops
  the node's actors. A peer that hears it sees the member `Leaving` (or already `Removed`) and stops probing it as `Up`.
- **A leaving member that goes quiet is removed.** If a `Leaving` member is unreachable, the leader removes it without
  `stableAfter`: it said it was going, and SWIM has already given it `ackWithin` and indirect probes. Gossip no longer
  counts it when deciding whether every member agrees (`converged`), or when sizing a side in `decide`.
- **A crashed `Up` member is unchanged.** It is still downed after `stableAfter`, then removed.

## Why this shape

Each half covers what the other cannot. The last word alone is a single message: if it is lost, or the peer is busy,
the peer waits `stableAfter` again. Early removal alone fixes a peer that saw the member `Leaving`, but not one that
still sees it `Up`, which the CI log shows does happen. Together, a lost farewell only costs a probe round, since the
next time the leaving member's gossip reaches anyone, it spreads.

**Alternative:** `Cluster.stop` waits, within `leaveWithin`, until every other member has acked its leaving. That is
more certain, but it puts a wait on the path to stop, and the last member to leave has nobody left to ack it.
Recommended: the last word plus early removal.

**Alternative:** with no member `Up` or `Joining`, a reachable `Leaving` member leads. `outFor` already lets such a
member go, so this changes nothing a test can see. Not recommended.

## Stack

- [x] **`spec-0233-leaving-quiet`** — the leader removes an unreachable `Leaving` member without `stableAfter`.
      `converged` and `decide` stop counting it. Lands with a `MembershipSuite` test: a member stops once it is seen
      leaving, a second member leaves, and every other view shows both gone within 5 s of simulated time.
      Done when: `:surefold-cluster:check` passes. The existing downing tests are unchanged and pass.
- [ ] **`spec-0233-farewell`** — `Membership.farewell()`, sent by `Cluster.stop` once `outFor` holds. Lands with the
      `MembershipSuite` test in Shape, and a `Net` helper (`stopped`) if the test needs one.
      Done when: the Shape test passes. Twenty local runs of `ClusteredSuite` "three nodes join from config alone…"
      each finish in under 5 s.

## Acceptance

```bash
./gradlew :surefold-cluster:check :surefold-app-cluster:check
for i in $(seq 20); do
  ./gradlew :surefold-app-cluster:test --tests '*ClusteredSuite*' --rerun -q || break
done
```

The test report shows the join test at about 2.5 s on every run. Over the next 50 CI builds on main, no cluster test
hits the 60 s limit.

## Open questions

Answered by the owner, who took each recommendation (2026-10-27).

1. **Does the farewell wait for an `Ack`?** No, it is sent once. Early removal covers a lost farewell within a probe
   round, and waiting would hold up stop.
2. **Is an unreachable `Leaving` member removed at once, or after a grace period?** At once. SWIM only calls a member
   unreachable after `ackWithin` and indirect probes, which is the grace period.
3. **Are `RebalanceSuite` and `AdminPageSuite` rechecked here or apart?** Apart. Run each 20 times after the second PR
   merges, and open an issue for any that still fails.
