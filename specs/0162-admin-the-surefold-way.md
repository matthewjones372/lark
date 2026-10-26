# 0162 — topics and groups administered the surefold way

## Problem

zio-kafka wraps Kafka's `Admin` client: about a hundred calls for topics, partitions, configs, consumer groups,
offsets, ACLs and quotas, each an effect with typed results. surefold-kafka has none; a service that creates its
topics at start-up, or resets a group's offsets in a runbook, uses `Admin` directly, with its `KafkaFuture`s and
exceptions.

## Not doing

- All hundred calls. ACLs, quotas, delegation tokens, client metrics and broker configs stay `Admin`'s.
- A topic-management DSL or migrations: Liquibase-style topic migrations would be their own spec.

## Shape

In `surefold-kafka`, which already brings `kafka-clients` and so `Admin`: the calls a service and a runbook use,
answering `Either` for the failures a caller acts on, blocking on a virtual thread as the rest of surefold does.

```kotlin
Kafka.admin(properties).use { admin ->
    admin.ensure(Topic("orders"), partitions = 6, replicas = 3, config = mapOf("retention.ms" to "604800000"))
    admin.topics(): Set<Topic>
    admin.describe(Topic("orders")): Either<NoSuchTopic, TopicDescription>
    admin.delete(Topic("orders.old"))
    admin.groups(): Set<String>
    admin.lag(group = "placing"): Map<TopicPartition, Long>
    admin.resetOffsets(group = "placing", Topic("orders"), to = From.Time(nineAm)): Either<GroupActive, Unit>
}
```

`ensure` is idempotent: it creates the topic, or adds partitions, and refuses to shrink.

## Why this shape

The few calls that come up in a service's start-up and a team's runbooks, with failures a caller can act on as
values (`GroupActive`: you cannot reset a group that has members). The alternative, wrapping all of `Admin`, is
a large surface to keep up with Kafka for calls almost nobody makes from application code.

## Stack

- [x] **`spec-0162-topics`** ([#713](https://github.com/matthewjones372/surefold/pull/713)): `admin`, `ensure`, `topics`, `describe`, `delete`.
- [ ] **`spec-0162-groups`**: `groups`, `lag`, `resetOffsets` (reusing 0159's `From.Time`).

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered (maintainer, 2026-10-26: go with the recommendations).

1. **In `surefold-kafka`, or a `surefold-kafka-admin` module?** Recommended: `surefold-kafka`; it adds no dependency.
2. **Which failures are values?** Recommended: `NoSuchTopic`, `TopicExists` (for a create that is not
   `ensure`), `GroupActive`; everything else a defect.
3. **Should `ensure` change an existing topic's config?** Recommended: no; report the difference as a value and
   leave the change to the caller.
