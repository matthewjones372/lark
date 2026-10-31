# 0261 - Explicit AWS KMS operations

## Problem

Spec 0260's explicit operations need an AWS adapter before an existing application can adopt them.
The coordinated spec's owner approved S2 and this stack on 29 October 2026. Approved source
provenance is merge commit `5fae2c9cf439d34e77c6fe86156966f189df777d`.

## Not doing

No changes to existing `Kms`, `AwsKms`, constructors, contexts, deletion semantics or implementers.
No application codecs, caches, indexes, eligibility policy, journal, roles, SSM templates or deployment.
No SDK upgrade, extra scheduler, generic STS service, automatic root creation or stable release here.

## Shape

Add `AwsKeyOperations` beside `AwsKms` in `surefold-tenancy-crypto-aws`, implementing spec 0260.
Accept explicit regional SDK clients and exact context maps, including legacy platform contexts.
Parse key ARNs and verify account, partition, Region and stable key identity against returned metadata.
Describe states and multi-Region relationships; aliases are observational lookups only. Read every
keys/tags page; malformed, cyclic or incomplete pagination cannot become a successful observation.
Failures contain only operation and a closed code; never SDK messages, contexts, policies or bytes.
Cancellation and programmer errors propagate. Safe operations retain bounded SDK retries.

Generate AES-256; encrypt existing bytes and decrypt with explicit root and exact context.
A preferred read Region may use a verified local replica without changing the stored primary ARN.
Verify local metadata's account, partition, logical MRK id and primary relationship without contacting
a failed primary. A rewritten ARN alone is not evidence. Routing grants no deletion authority.

Creation is separate primary, alias, rotation and replica steps for caller-owned durable checkpoints.
Supply explicit policy, description and tags; create symmetric ENCRYPT_DECRYPT multi-Region roots.
CreateKey has no idempotency token: enforce single-attempt creation even for borrowed clients.
A lost mutation reply is uncertain, never permission to create again. Never reassign alias conflicts.
Rotation targets primaries. Replicas get explicit regional policy/tags; existing replicas are verified;
Creating stays retryable. No implicit material replacement, disable, cancellation or policy changes.

Deletion validates every expected location, relationship and required tag subset before mutation.
Waiting days must be 7..30. Schedule replicas before their primary; retain aliases. Acknowledge
pending states with provider dates, including an unknown primary date while replicas remain.
On a state race, re-describe and accept only a confirmed pending state. Keep regional partial progress
on later errors or lost replies. Missing/incomplete metadata remains pending evidence, not proof of
destruction. The caller decides business eligibility and the explicit regional deletion authority.

Use Surefold `Resource` for owned clients, reverse release and partial acquisition rollback.
Borrowed clients and credential providers remain caller owned. Preserve SDK credential refresh.
Use separate single-attempt creation clients where necessary; expose the constraint, not a promise
of exactly-once creation. No custom retry threads, polling or credential caches.

## Why this shape

An additive adapter preserves existing callers and stored ciphertext. The SDK owns transport and
credential refresh; the library owns provider operations and truthful progress. The application owns
policy, durable creation journals and creator/rollback fencing before its management cutover.

## Stack

The owner approved these entries before the first commit; main/build/docs target ~200 lines each.

- [ ] **`codex/kms-s2-spec`** - register the approved S2 requirements.
- [ ] **`codex/kms-s2-foundation`** - ARN validation and sanitised failures.
      Done when: invalid identities fail before calls; expected failures redact all provider details.
- [ ] **`codex/kms-s2-observations`** - complete metadata and pagination.
      Done when: stable relationships and full tags/roots are verified without discovery mutations.
- [ ] **`codex/kms-s2-data`** - exact-context data operations and regional reads.
      Done when: explicit roots/contexts round-trip and local replica evidence supports DR reads.
- [ ] **`codex/kms-s2-creation`** - separate creation, alias and rotation steps.
      Done when: uncertain creation is not retried, alias conflicts persist and rotation is primary-only.
- [ ] **`codex/kms-s2-replicas`** - replica creation and verification.
      Done when: existing relationships are verified and Creating remains retryable.
- [ ] **`codex/kms-s2-deletion`** - guarded regional deletion progress.
      Done when: complete preflight precedes replica-first calls; races/partial progress remain truthful.
- [ ] **`codex/kms-s2-clients`** - owned/borrowed resources and verification/release documentation.
      Done when: ownership/rollback and SDK wire behaviour pass; real AWS release gates are explicit.

## Acceptance

```bash
./gradlew :surefold-tenancy-crypto:build :surefold-tenancy-crypto-aws:build
./gradlew :surefold-app:test --tests '*SpecsSuite' --tests '*UnnamedSuite'
./gradlew spotlessApply
./gradlew build
```

Protocol tests cover retry counts, exact context, pagination, errors and resource ownership alongside
legacy caller tests. Real AWS regional and least-privilege evidence is required before stable release;
local tests cannot prove it. Prepare reviewable verification steps without activating the consumer.

## Open questions

None: the five coordinated decisions and this S2 stack are approved. Real AWS verification and
stable publication remain explicit release gates; consumer journal/fencing remains its own work.
