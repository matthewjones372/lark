# 0266 - Resource cleanup preserves primary identity and completes release

**WITHDRAWN, 31 October 2026. Do not merge or implement this proposal.**
The proposed missing guard was refuted by actual baseline qualification: all13 ResourceTest cases passed,
including two private same-instance/three-release cases, on unchanged Resource source. Compiled ResourceKt
calls Kotlin's addSuppressed extension, whose identity guard already avoids Java self-suppression.
No runtime change, bug fix or manufactured RED occurred. The original proposal remains below solely to
preserve the public history of the corrected inference; it is not a prerequisite for key retention work.

Approved under delegated P04 root/KMS review of private draftc36e8dc8; no implementation or shipped fix claimed.
Checked main e7af9df77565b76c18624ffee6398dde74b0414c and Maven0.17.0 sources.
Resource.kt is byte-identical there, SHA256f83e8caf103d77e5d32cafd93486f96c28fd59ac2fd8c58dae949c9169108196.
Open PRs955,954,948–941 have no Resource.kt/ResourceTest.kt/0266 change at checked heads.

## Problem
Resource already promises every release in reverse order, original body failure retained and cleanup failures suppressed.
Its private orThrowFrom currently calls carried.addSuppressed(cleanup) even when both references are identical.
Throwable rejects self-suppression; the resulting IllegalArgumentException escapes releaseAll's fold, changes the
primary and can skip remaining releases. This affects a body and release sharing one Throwable, or two releases
throwing the same object after a successful body. Retained-key bounded drains must not assume that missing guarantee.

## Not doing
No new primitive, API, dependency, concurrency, retry, exit-case classification or resource-acquisition policy.
No cache/material/consumer pin, provider action, broad test migration or change to distinct suppression ordering.

## Shape
In existing core/surefold/src/main/kotlin/io/github/matthewjones372/surefold/Resource.kt,
change only the private orThrowFrom suppression branch: retain the carried Throwable when cleanup === carried;
otherwise keep adding the cleanup to it as today, or choose cleanup as primary when no failure was carried.
Use reference identity, not equals. Preserve original Throwable objects, reverse release order, ExitCase and all signatures.
Skipping self-suppression lets the existing fold reach later releases; no local replacement drain/exception framework.

## Why this shape
A small correction to the existing Resource contract serves every caller and avoids duplicate identity guards in caches.
Alternative: local key-cache cleanup wrapper; rejected because the defect and promised release semantics are generic.

## Stack
- [ ] codex/key-view-resource-prerequisite: reviewed numbered contract and canonical path entitlement published BEFORE tests/source,
      then the private identity guard and two regressions in existing core/surefold/src/test/kotlin/io/github/matthewjones372/surefold/ResourceTest.kt.
      One bounded implementation PR, estimated2 changed main lines plus this spec; tests excluded from Surefold's200 cap.
      Spec0265 records the prerequisite; no retention all-release guarantee is credited until correction PR/main proof passes.
No extra spec-main wait is introduced here; the reviewed specification must be committed/published before implementation.

## Acceptance
First add both real Resource regressions to the existing Jupiter ResourceTest; retain compile success and meaningful RED:
- A body throws primary P. Register releases so actual order is same-P throw, distinct-D throw, then successful last release.
  The SAME P returns, suppressed is exactly[D], and all three release events occur in reverse acquisition order.
- The body succeeds. Release order is first-M throw, repeat-SAME-M throw, then successful last release.
  The SAME M returns, suppressed is empty, and the third release runs. No helper mock or mirrored implementation.
Both fail on the unmodified source with self-suppression/incorrect primary; run them before the minimal guard.
Keep all11 existing ResourceTest cases and assertions unchanged, including distinct failure and reverse-order controls.
After the guard, run ResourceTest, typed detektMain/detektTest and normal spotlessApply/build with dependency/90% gates.
Record actual execution/cache/skips; no retries, threshold/suppression/build edits, or all-fresh claim.
Required exact-head/current-base CI and actual-main proof precede claiming the Resource correction delivered.

## Open questions
None. Number0266 was rechecked before publication against current maine7af (highest committed0265) and every open
PR path list (955,954,948–941); no conflict. The approved contract and exact scope are published before tests/source.
