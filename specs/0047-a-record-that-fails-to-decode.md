# 0047 — A record that fails to decode

## Problem

0046 decodes a record with Kafka's own `Deserializer`, inside the connector's
poll. A deserializer that throws fails the consumer stage, so the run ends
`Died`. `restartOnDefect` then starts again from the last commit and reads the
same record, so one bad record stops the partition for good.

Avro with a schema registry makes this worse, because Confluent's
`KafkaAvroDeserializer` throws `SerializationException` for two different
things:

- **The record is bad**: a wrong magic byte, a schema id the registry does not
  know, or a corrupt payload. Only that record is affected, and retrying never
  helps.
- **The registry is unreachable**: a refused connection or a 5xx. Every record
  is affected, and a retry is exactly the fix.

A service that sends both to a dead-letter topic floods it whenever the
registry blips. A service that restarts on both is stopped by the first bad
record.

## Not doing

- **No Avro in lark.** `lark-kafka` depends on no Avro library and no registry
  client. It takes any `Deserializer`, and Confluent's, Apicurio's and avro4k's
  are all one.
- **No dead-letter producer.** `divertLefts` takes a function. Publishing to a
  dead-letter topic arrives with 0048.
- **No schema compatibility check.** Golden `.avsc` files checked against the
  registry are a later spec.
- **No change to 0046's `subscribe`.** It stays for deserializers that cannot
  fail, such as `String` and `Long`.

## Shape

```kotlin
val orders: Decoder<Order> = Decoder(KafkaAvroDeserializer(registry), transient = ::registryDown)

Kafka.subscribe(bytes, Topic("orders"), key = Decoder.string(), value = orders)
    // Stream<Nothing, Committed<Either<DecodeError, ConsumerRecord<String, Order>>>>
    .divertLefts { bad -> deadLetters.send(bad) }  // runs before the offset moves on
    .mapParRecord(4) { record -> shop.place(record.value()).bind() }
    .restartOnDefect(Schedule.exponential(100.milliseconds))  // a registry that is down lands here
    .runCommitting(committer)

fun registryDown(t: Throwable): Boolean =
    t.causes().any { it is IOException || (it is RestClientException && it.status >= 500) }
```

- **The connector reads bytes.** `subscribe` takes
  `ConsumerSettings<ByteArray, ByteArray>` and decodes in the stream, not in
  the poll. That puts the decision about each failure in lark's hands.
- **`Decoder(deserializer, transient)` classifies each throw.** A throw that
  `transient` accepts stays a defect, so the run dies and restarts. Any other
  throw is `Left(DecodeError)`.
- **`DecodeError` carries the record's position, its raw content and the
  cause:** `topic`, `partition`, `offset`, `part` (`Key` or `Value`), the raw
  `key` and `value` bytes, `headers`, and `cause`. That is everything a
  dead-letter record needs.
- **`divertLefts` on `Committed<Either<L, R>>`** takes `(L) -> Unit`, not a
  `Sink`. The function runs, and only then does the record's offset move on as
  a filtered record's does. A function that throws is a defect, so the record
  is not committed.
- **`absolve()` on `Committed<Either<L, R>>`** ends the stream `Failed(l)`, for
  a service where a bad record should stop everything.

## Why this shape

Decoding in the stream, not inside the poll, is the only place where a throw
can become a value without the consumer dying. The connector's poll has no
declared-failure channel. The alternative is a wrapping `Deserializer` that
returns a sentinel inside the poll: it keeps the connector's typed settings,
but it hides the failure in the element type through a cast. A `Sink` cannot
be `divertLefts`' target, as it is on a plain stream, because a sink's write is
not ordered with the commit. The offset could move past a dead letter that was
never written. A function run in the stream is ordered with it.

## Stack

- [ ] **`spec-0047-decode-in-stream`**: `Decoder`, `DecodeError`, the bytes
      form of `subscribe`.
      Done when: a record whose deserializer throws `SerializationException`
      is a `Left` with its offset and raw bytes, and one whose cause is an
      `IOException` ends the run `Died`. After `restartOnDefect`, that record
      is read again.
- [ ] **`spec-0047-committed-either`**: `divertLefts` and `absolve` over
      `Committed<Either<L, R>>`.
      Done when: a diverted record is committed only after its function
      returns, and a function that throws leaves the record uncommitted.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Does `transient` have a default?** Recommend no. The obvious default,
   "an `IOException` somewhere in the causes", misses Confluent's 5xx, which
   arrives as a `RestClientException`, and floods the dead-letter topic. Make
   the service name the rule, and put a Confluent recipe in `docs/`.
2. **Does `DecodeError` keep the raw bytes?** Recommend yes. A dead letter
   without the payload cannot be replayed. The size is already bounded by the
   broker's record limit.
3. **Is there one decoder for key and value, or two?** Recommend two, as in the
   sketch. Keys are often strings when values are Avro, and `part` says which
   one failed.
4. **Should the petshop use Avro to prove this?** Recommend yes, with avro4k and
   a registry in Testcontainers. Checking whether avro4k works with
   Confluent's wire format is the first task on that branch.
