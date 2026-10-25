# 0161 — a broker for your own tests

## Problem

lark-kafka's own tests run against a broker in the test JVM (`KafkaBroker`, over embedded-kafka), with helpers to
send, read, create topics and read a group's committed offsets. Since spec 0193 a suite gets it as a lark-junit
`Resource`, `kafkaBroker()`. All of it is in `src/test`, copied into lark-kafka and lark-kafka-pekko, so a service
using lark-kafka writes its own. zio-kafka ships `zio-kafka-testkit` for the same job.

## Not doing

- Testcontainers: it needs Docker, and the point of `KafkaBroker` is that it does not.
- A fake broker: tests that commit and rebalance need the real protocol.

## Shape

A module, `lark-kafka-test`, holding what `lark-kafka/src/test` has, made public, in lark-kafka's package. A
lark-junit suite shares it as a `Resource`, as lark's own suites do:

```kotlin
object OrdersSuite : Suite<KafkaBroker>() {
    override val shared = kafkaBroker()

    init {
        test("an order is placed once") { kafka ->
            val orders = Topic("orders")
            kafka.create(orders, partitions = 3)
            kafka.send(orders, "o-1")
            // … run the service's stream against kafka.bootstrap …
            kafka.committed(group = "placing", topic = orders) shouldBe 1L
        }
    }
}
```

A test class on plain Jupiter registers the same class as an extension, started before its tests and stopped
after them:

```kotlin
class OrdersTest {
    companion object {
        @JvmField @RegisterExtension
        val kafka = KafkaBroker()
    }
}
```

`lark-kafka`'s and `lark-kafka-pekko`'s own tests then use the module, so the helpers are tested by being used,
and the copy in each goes.

## Why this shape

The same class lark's own suites already rely on, rather than a new design: it has been through port races
(#436), transactions and fatal-exit handling. Both hooks, because lark's suites are lark-junit and most services'
are Jupiter; the extension is two callbacks over `start` and `stop`.

## Stack

The move of `KafkaBroker` alone is about 220 lines of main source, so entry 1 is over the soft cap; it is code
moved, not written.

- [x] **`spec-0161-module`** ([#628](https://github.com/matthewjones372/lark/pull/628)): `lark-kafka-test` with `KafkaBroker`, its helpers by `Topic`, and `kafkaBroker()`;
      every place a new module must be named (`docs/agents/new-module.md`); lark-kafka's tests moved onto it.
      Done when: `NoOtherDependenciesTest` allows exactly lark-kafka, embedded-kafka and JUnit.
- [x] **`spec-0161-jupiter`** ([#645](https://github.com/matthewjones372/lark/pull/645)): `KafkaBroker` as a JUnit 5 extension, with a test on Jupiter; lark-kafka-pekko's
      tests moved onto the module and its copy deleted; a section in `docs/kafka.md`.
      Done when: no `KafkaBroker` is left in any module's `src/test`.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered (maintainer, 2026-10-25).

1. ~~Its own module, or inside `lark-test`?~~ Its own; `lark-test` must not bring a Kafka broker to every service
   that tests anything.
2. ~~embedded-kafka's Scala on the test classpath of services that use it?~~ Accepted, as a test dependency only.
3. ~~Helpers by `String` topic, or `Topic`?~~ `Topic`, as the rest of lark-kafka.
4. ~~A lark-junit `Resource`, a JUnit 5 extension, or both?~~ Both.
