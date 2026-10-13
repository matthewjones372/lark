# 0117 — A topic a test can read

## Problem

A test that wants to know what a service wrote to Kafka builds a
`KafkaConsumer` by hand: bootstrap servers, a group id, `earliest`, two
deserializers. Then it polls in a loop until something arrives or a deadline
passes. The petshop's `KafkaBusSpec` does this three times, plus an `Admin`
client to read a committed offset. Each loop picks its own timeout. Each
consumer joins a group, so a test that reads a topic can disturb a group it
did not mean to. And "nothing arrived" looks the same as "not yet".

## Not doing

- **A broker.** A test brings its own, embedded-kafka or Testcontainers. This
  module takes a bootstrap string.
- **Producing.** `KafkaProducer` in a test is already one line plus `get()`.
- **Schemas or Avro.** The test passes the deserializer the service uses.
- **Waiting.** `eventually` is [0115](0115-a-test-that-reads-as-a-story.md)'s;
  this module answers "what is there now".

## Shape

A new module, `lark-kafka-test`, depending on `lark-kafka` (and so
`kafka-clients`) and nothing else:

```kotlin
val events = TopicReader(Topic("shop-events"), kafka.bootstrap, StringDeserializer(), ShopEventDeserializer(registry))

events.records()          // List<ConsumerRecord<String, ShopEvent>>, every partition, oldest first per partition
events.values()           // List<ShopEvent>
events.keys()             // List<String>

committedOffset(kafka.bootstrap, group = "projection", Topic("shop-events"))   // summed over partitions; 0 for none

// with 0115
eventually(5.seconds) { events.values().filterIsInstance<PetAdopted>().single().by shouldBe "Ada" }
```

- **`records()` is a snapshot.** It reads each partition's end offsets, then
  polls until the consumer has reached them. So the result is what the broker
  held at the call, with no timeout of its own, and it returns at once for an
  empty topic.
- **It never joins a group.** `assign` rather than `subscribe`, with no group
  id and no commits, so reading a topic changes nothing the service sees.
- **A record that cannot be decoded throws**, naming the topic, partition and
  offset, with the deserializer's exception as the cause. A test that expects
  junk reads with a `ByteArrayDeserializer`.
- `TopicReader` is `AutoCloseable`, and holds one consumer across calls.

## Why this shape

Reading up to the end offsets turns "poll until something turns up" into a
question with an answer. Waiting is left to `eventually`, which already knows
about clocks, tries and failure messages. The alternative is a
`shouldEventuallyContain` built on poll timeouts, which mixes the two and
repeats 0115. Recommended: snapshot here, wait there.

## Stack

- [ ] **`spec-0117-topic-reader`** — `TopicReader`, `committedOffset`, and a
      `NoOtherDependenciesTest`.
      Done when: against an embedded broker, `records()` on a topic holding
      three records across two partitions answers all three and returns
      without waiting; on an empty topic it answers an empty list at once; a
      reader's reads leave `committedOffset` for every group unchanged; an
      undecodable record throws naming its offset.

## Acceptance

```bash
./gradlew :lark-kafka-test:test
```

Then the petshop's `KafkaBusSpec` loses its three poll loops and its `Admin`
client.

## Open questions

- **`committedOffset` summed, or per partition?** The petshop's topics have one
  partition. Recommended: summed, plus `committedOffsets` answering a map, for
  a test that cares which.
- **Ship a JUnit extension for a broker?** embedded-kafka is a Scala artifact
  with its own Kafka build. Recommended: no; a broker is a choice the test
  makes.
- **A snapshot of a topic still being written** can read a partition up to an
  end offset that has already moved. Fine for `eventually`, which asks again.
  Say so in the KDoc? Recommended: yes.
- **Name:** `TopicReader`, or `Kafka.reading(topic, …)` beside `Kafka.consume`?
  Recommended: `Kafka.reading`, so it is found where consuming already is.
