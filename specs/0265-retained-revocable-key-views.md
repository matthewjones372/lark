# 0265 — Retained keys with revocable owned views

Spec-publication proposal after independent contract review; no implementation or shipped API is claimed.
Checked main10632ad / sourcec6d5465d / release0.17.0. Implementation starts only after this spec lands on main.

## Problem

KeyCache deliberately gives independent DataKey copies. Ordinary eviction cannot revoke a copy already in use.
Applications needing both degraded operation and immediate revocation otherwise duplicate key lifetime machinery.
KeyCache must retain its Unavailable-only fallback and owner budget; generic Cache has different policy (spec0246).

## Not doing

Nothing to SQL, schemas, authority/journals, codecs, provider transports, application policy, or default time/capacity.
No replacement of KeyRing.current/version/versions, old copy semantics, new module or generic lease framework.

## Shape

These are PROPOSED additions in the existing crypto package; loader is today's function shape, not a shipped KeyLoader.
```kotlin
class KeyIdentity(val owner: KeyOwner, val purpose: KeyPurpose, val version: Int, revision: ByteArray)
interface KeyView : AutoCloseable {
    val identity: KeyIdentity
    fun <A> using(block: (ByteArray) -> A): Either<KeyError, A>
    override fun close()
}
// Proposed members of existing KeyCache; its constructor and existing members stay unchanged:
fun lease(identity: KeyIdentity, load: () -> Either<KeyError, DataKey>): Either<KeyError, KeyView>
fun revoke(owner: KeyOwner)
fun revokeAll()
fun closeAndJoin()
```
KeyIdentity defensively owns nonempty revision bytes, content equality/hash and fixed value-free toString;
owner/purpose/version are read-only; version is positive. No revision byte getter or mutable collection escapes.
Revision is the caller's immutable durable-key identity, not an epoch or a hash the library invents/authorises.
The caller guarantees interchangeable loaders for equal identities; the returned DataKey metadata must match.
Legacy unqualified Slot/get and qualified lease have distinct tagged flight/retention modes: neither can satisfy
the other, even for the same owner/purpose/version. Old handed-out DataKey copies remain UNREVOCABLE.
Both modes share ONE owner LRU at the instance capacity (default1000), evict/revoke retention and one retained tagged value per logical slot
(owner,purpose,requested version; the legacy active alias keeps its existing null version). Revision variants add
no retained map entries. Each accepted load has a monotonic slot ticket; only the highest-ticket successful Right
may replace retention. A slower older-ticket Right answers its own callers unretained, never overwriting it.
A later-ticket failure does not displace an older success; fallback never advances tickets or resets key age.
Tickets order cache admission, never opaque revisions. Forgotten/revoked epochs fence late publication; later
admissions cannot join an earlier epoch's flight. Different qualified identities never share material/fallback.
lease uses the call-bound clock and instance soft/hard lifetimes (defaults15m/60m); no extra capacity, failure TTL or identity ordering is added.
Only Unavailable may reuse that identity's last good key before hard; Denied/Invalid/NoTenant refuse/drop it.
Each view captures the injected clock.get() at its lease admission; later using uses that SAME Clock, not its
thread's current binding or Instant.now. Material's age/deadline starts on its load's captured clock before load;
fallback/view creation never resets it. After waiting a loader, recheck the original good material's deadline:
Unavailable begun at55m but answered at61m refuses, even if it was usable when loading started.
Every using checks hard expiry at entry AND after a normally returning callback, returning fixed
KeyError.Unavailable("the key view has expired") at/after deadline; expired cold load also refuses/wipes.
Failure/expiry cleanup may remove ONLY its owned source matched by mode/identity/ticket/epoch, never newer
retention, including a newer publication of the SAME identity. No caller side effects are rolled back by guards.
Old get/Slot, KeyCache constructor/defaults, KeyRing/DataKey descriptors and old independent-copy behavior remain.

The loader owns temporary/partial bytes until Right returns; then ownership of that DataKey material transfers.
The cache owns every transferred array even on metadata refusal, cancellation, admission/publication rejection.
A view owns a revocable guarded borrow, never an independently exported DataKey. using exposes material only
for its callback; retaining/copying it outside that contract is unsupported. Original callback defects/cancellation
propagate unchanged. Closed/revoked using returns KeyError.Denied("the key view is unavailable") before entry.
If revoked during a normally returning callback, it returns that fixed Denied, never success. close is idempotent.
Each same-key concurrent using gets operation-owned working bytes; wipe them on every callback exit. Closing a
view prevents new uses; an admitted callback may return normal A after benign close/forget ONLY while neither
revoked nor expired, then its bytes wipe. Revoke gives fixed Denied; expiry gives fixed Unavailable.
Original callback defects/cancellation always propagate; the post-callback guard cannot undo escaped side effects.
Existing evict(owner) forgets BOTH modes' retention: borrowed views may finish; bytes wipe once ownership permits.
revoke(owner)/revokeAll invalidate affected held views including previously forgotten ones and best-effort-zero
their owned copies; it drops both modes' retention but cannot revoke legacy copies. A qualified load paused
across revoke returns fixed Denied, unkept; a legacy load may still return its old-style independent copy, unkept.
Forgetting across a load suppresses retention but may answer accepted borrowers. Every unretained transferred
array has a terminal owner and wipe: no onEvict-only assumption, abandoned flight copy or orphaned view.
closeAndJoin closes admission, revokes views, cancels/joins accepted loaders and waits admitted using callbacks;
idle handles need not close. It completes cleanup despite interruption, restores interruption and retains failures.
Calling it from its own loader/using is refused by fixed IllegalStateException before changing admission.
Later lease returns fixed Denied; old internal get after close throws fixed IllegalStateException. No forced timeout.
Use existing SingleFlight/Shared/Resource only; no effects in retryable Shared callbacks or new executor/scheduler.

## Why this shape

Extend KeyCache additively: its per-call loader/clock, selective stale and owner bound already fit (PR903).
Alternative: a general retained-resource cache; broader than this key requirement. Recommend this crypto-only view.

## Stack

- [ ] **`codex/key-view-contract`** — reviewed numbered spec only on main; numbering checked before publication.
- [ ] **`codex/key-view-ownership`** — identity and private owned material/view guards; deterministic lifetime tests.
- [ ] **`codex/key-view-retention`** — KeyCache lease/evict/revoke, exact-identity soft/hard and all ownership exits.
- [ ] **`codex/key-view-release`** — joined close, old source/binary fixtures and compiled guide; no consumer pin yet.
Each implementation entry stays under~200 changed main/build/docs lines; split/review scope before exceeding it.
No snapshot consumer is enabled before the complete reviewed stack and required main checks pass.

## Acceptance

```bash
./gradlew :surefold-tenancy-crypto:check :surefold-app:check
./gradlew spotlessApply && ./gradlew build
```
Old copies survive evict; new views survive forget, refuse revoke before/during use and across a paused load.
59m Unavailable succeeds/61m refuses; paused55→61m fallback refuses without resetting age; held-view using at
hard refuses before callback, and a callback crossing hard cannot return Right. Capture-clock rebinding is inert.
Denied/Invalid/NoTenant never degrade; changed identity never degrades; old-epoch failure/expiry cannot drop newer
SAME-identity retention. Benign close admits normal callback result only before expiry and absent revocation.
Assert one load, owner LRU, every transferred/working array wiped, defects/cancellation identity and joined close;
owned Promise gates always release/join. No sleeps, lowered coverage/dependency gates or algorithm/provider change.

## Open questions

Resolved after independent review: extend KeyCache rather than add a second cache; close joins accepted work,
not idle handles; material access is callback-bounded, with no escaped-copy/JVM-erasure guarantee. Tagged modes,
one retained identity per slot, successful-ticket publication, original hard expiry and benign close versus
revoke follow the Shape above. There are no remaining implementation choices within this bounded contract.
