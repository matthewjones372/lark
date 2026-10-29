# 0260 - Explicit KMS operations beside the tenant-key port

## Problem

An application with existing protected data cannot adopt `AwsKms` if adoption changes its context,
aliases or deletion evidence. A consumer's approved spec separates reusable KMS operations from its
business rules. Its owner approved all five decisions and this S1 stack on 29 October 2026.
Approved consumer-spec provenance: merge commit
`5fae2c9cf439d34e77c6fe86156966f189df777d`; implementation was explicitly requested afterwards.

## Not doing

No change to `Kms`, `KeyContext`, `KeyError`, `InMemoryKms`, the `AwsKms` constructor or their
existing semantics. No field codec, index, key cache, SQL schema, audit or application policy changes.
No AWS implementation, IAM, deployment or consumer migration in S1; those follow the published extension.

## Shape

Add `KeyOperations` and neutral immutable request/result types in `surefold-tenancy-crypto`.
It accepts explicit roots and exact context maps; it does not insert tenant/platform defaults.
A context and creation request redact their contents when rendered. Expected failures are values:
invalid input/ciphertext, refusal, unavailable, not observed, incomplete evidence, conflict and
uncertain mutation. No AWS exceptions, policy text or key material in failure messages.

Data operations generate AES-256, wrap existing bytes and unwrap under the supplied context/root.
Management operations describe, resolve an alias without creation, read full tags, create a primary,
bind an alias, enable rotation, create/inspect a replica, and schedule an expected regional key set.
Metadata describes account/partition/Region/state and primary/replica relationships. Deletion reports
regional progress and a failure separately; effective dates can be unknown. Validate all supplied
ownership evidence before any destructive operation, schedule replicas first, retain aliases and
recognise accepted pending states without resetting the waiting period. Unknown absence is not proof
of destruction. Invalid waiting days fail without mutation. Eligibility remains the caller's policy.

Add an independent in-memory implementation with the same contract and deterministic test clock.
It keeps state in `Shared`; encryption binds a collision-free encoding of the exact context map.
Two contexts can share a root without opening each other's wrappers. Expose safe test controls for
unavailable/incomplete/uncertain outcomes and metadata needed to prove restart/partial progress.
This fake cannot prove IAM or AWS eventual consistency. Old implementations remain untouched.

## Why this shape

An additive capability preserves existing implementers, contexts and alias-removing deletion semantics.
Changing the current port would break callers; adopting every crypto layer would also require codec
and revocation migrations. The existing modules own this capability, so no new repository is needed.
The application retains roles, aliases, templates, ownership, privacy ledger and durable checkpoints.
Creation is not exactly once because an alias is checked; a lost create reply stays unresolved.

## Stack

The owner approved these S1 branches before the first commit. Main sources/build/docs target the
repository's 200-line soft cap per implementation entry; tests do not count.

- [ ] **`codex/kms-s1-contract`** - operation types/interface and old-caller compatibility tests.
      Done when: exact contexts, sanitised failures, metadata/progress and all capabilities compile
      beside unchanged legacy API, and no AWS dependency enters the neutral module.
- [ ] **`codex/kms-s1-memory-data`** - in-memory generation/wrap/unwrap and shared state.
      Done when: exact tenant/platform contexts round-trip, wrong root/context fails and input
      mutation does not change the captured context; existing callers still pass.
- [ ] **`codex/kms-s1-memory-lifecycle`** - in-memory metadata, creation steps and deletion progress.
      Done when: ownership is checked before mutation, aliases remain, retries retain waiting dates,
      regional partial results survive and uncertain outcomes never become false success.

AWS transport/release work is S2 of the approved source spec, followed by consumer D1-D5. It requires
real regional/policy evidence and preserves the application's creation-journal/rollback gates.

## Acceptance

```bash
./gradlew :surefold-tenancy-crypto:build :surefold-tenancy-crypto-aws:build
./gradlew :surefold-app:test --tests '*SpecsSuite'
./gradlew spotlessApply
./gradlew build
```

## Open questions

None of the five approved owner decisions remains open. The local public names above implement the
approved additive shape. Real AWS creation/replication reconciliation, client retry configuration and
resource ownership are S2 acceptance; application journal/creator fencing remains consumer D3.
The coordinated spec's exact-context, least-privilege, truthful progress and compatibility requirements
remain normative. Changes to those requirements need owner approval.
