# 0161 — a broker for your own tests

## Problem

lark-kafka's own tests run against a broker in the test JVM (`KafkaBroker`, a JUnit 5 extension over
embedded-kafka), with helpers to send, read, create topics and read a group's committed offsets. All of it is in
`src/test`, so a service using lark-kafka writes its own. zio-kafka ships `zio-kafka-testkit` for the same job.

## Not doing

- Testcontainers: it needs Docker, and the point of `KafkaBroker` is that it does not.
- A fake broker: tests that commit and rebalance need the real protocol.

## Shape

A module, `lark-kafka-test`, holding what `lark-kafka/src/test` has, made public:

```kotlin
class OrdersTest {
    companion object {
        @JvmField @RegisterExtension
        val kafka = KafkaBroker()
    }

    @Test fun `an order is placed once`() {
        kafka.create("orders", partitions = 3)
        kafka.send("orders", "o-1")
        // … run the service's stream against kafka.bootstrap …
        kafka.committed(group = "placing", topic = "orders") shouldBe 1L
    }
}
```

`lark-kafka`'s own tests then use the module, so the helpers are tested by being used.

## Why this shape

The same class lark's own suite already relies on, rather than a new design: it has been through port races
(#436), transactions and fatal-exit handling. JUnit 5 only, because that is what lark's
tests and `lark-test` use.

## Stack

- [ ] **`spec-0161-module`**: `lark-kafka-test` with `KafkaBroker` and its helpers, every place a new module
      must be named (`docs/agents/new-module.md`), and lark-kafka's tests moved onto it. Done when:
      `NoOtherDependenciesTest` allows exactly lark-kafka, embedded-kafka and JUnit.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Its own module, or inside `lark-test`?** Recommended: its own; `lark-test` must not bring a Kafka broker
   to every service that tests anything.
2. **embedded-kafka's Scala on the test classpath** of services that use it. Recommended: accept it, as a test
   dependency only.
3. **Helpers by `String` topic, or `Topic`?** Recommended: `Topic`, as the rest of lark-kafka.
