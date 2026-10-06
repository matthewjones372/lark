# lark-kafka

**A Kafka record is committed only after the work on it is done.** A
subscription is a `Stream` of `Committed` elements, each carrying its record's
offset, and the only way to run one is `runCommitting`. Forgetting to commit,
or committing before the work, does not compile.

Everything below is in `io.github.matthewjones372.lark.kafka`. Specs
[0053](../specs/0053-a-record-that-commits-after-it-is-handled.md),
[0054](../specs/0054-a-record-that-fails-to-decode.md),
[0056](../specs/0056-kafka-on-any-backend.md),
[0087](../specs/0087-a-record-written-to-kafka.md) and
[0124](../specs/0124-a-record-written-exactly-once.md) give the reasons.

## Picking a backend

The pipeline is written once. Which backend runs it is named where the run
starts, as for any other stream:

```kotlin
import io.github.matthewjones372.lark.kafka.Kafka
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.kafka.consume
import io.github.matthewjones372.lark.kafka.mapRecordOrFail
import io.github.matthewjones372.lark.kafka.runCommitting
import io.github.matthewjones372.lark.stream.Forks
import io.github.matthewjones372.lark.stream.PekkoStreams
import io.github.matthewjones372.lark.stream.start
import org.apache.kafka.common.serialization.StringDeserializer

val placing = Kafka.consume(consumerProperties, Topic("orders"), key = StringDeserializer(), value = StringDeserializer())
    .mapRecordOrFail { record -> shop.place(record.value()).bind() }
    .runCommitting()

placing.start(Forks())                  // one virtual thread polls, handles and commits
placing.start(PekkoStreams(system))     // the same description on Pekko Streams
```

`Kafka.consume` is one `KafkaConsumer` per run, read through
`Stream.blocking`, and it is in `lark-kafka`, which brings no backend at all.
Only the thread that polls touches the consumer. It commits what
`runCommitting()` marked handled before each poll, for the partitions it loses
in a rebalance, and when the run ends, and a run's exit completes only after
that last commit. A `stop()` wakes a poll that is waiting on a quiet topic.

`Kafka.subscribe` is Pekko's own Kafka connector, in `lark-kafka-pekko`: a
backpressured prefetch, commits batched by its committer sink, and a `stop()`
that drains. Pekko runs it and nothing else does, and it ends on
`runCommitting(settings)`.

```kotlin
dependencies {
    // Any backend: lark-stream and kafka-clients come with it, and the backend is the service's own choice.
    implementation("io.github.matthewjones372:lark-kafka:0.9.0")
    // Or Pekko's connector: lark-kafka, lark-stream-pekko and pekko-connectors-kafka come with it.
    implementation("io.github.matthewjones372:lark-kafka-pekko:0.9.0")
}
```

| | `Kafka.consume` | `Kafka.subscribe` |
|---|---|---|
| Runs on | Forks, Pekko, TestStreams | Pekko |
| Ends on | `runCommitting(): Run<E, Long>`, the count of elements | `runCommitting(settings): Run<E, Done>` |
| Commits | on the polling thread, before each poll and on close | through the connector's committer sink, in batches |
| `mapParRecord`, `divertLefts` | yes, on Forks as a window of bodies in flight (0051) | yes |
| `restartOnDefect` | yes, on every backend; a restart closes the failed consumer before it opens the next | yes |

## Operators

| Call | Answers |
|---|---|
| `Kafka.consume(properties, vararg topics, key: Deserializer<K>, value: Deserializer<V>)` | `Stream<Nothing, Committed<ConsumerRecord<K, V>>>` on any backend |
| `Kafka.consume(properties, vararg topics, key: Decoder<K>, value: Decoder<V>)` | the same over bytes, each record decoded in the stream: `Committed<Either<DecodeError, ConsumerRecord<K, V>>>` |
| `Kafka.subscribe(settings, vararg topics)` and its `Decoder` form | the same two, through Pekko's connector |
| `mapRecord`, `mapRecordOrFail`, `mapParRecord`, `mapParRecordOrFail`, `filterRecord`, `mapConcatRecord` | lark-stream's operator of the same stem, with the body on the value and the offset carried. `Record` because the same names beside lark-stream's would be ambiguous |
| `divertLefts(to: (L) -> Unit)` | each `Left` to a function, in order, before anything after it moves on; a throw from it is a defect and the record is not committed |
| `absolve()` | the first `Left` ends the run `Failed` |
| `publishTo(producer) { a -> record }`, `publishRecord(producer) { value -> record }` | each element sent, and passed on in order once the broker has it; see below |
| `transacted(producer) { value -> records }`, `runTransactionally(...)` | each batch's outputs and offsets in one transaction; see *Exactly once* |
| `runCommitting()` / `runCommitting(settings)` | the run, committing each offset once its record's element reaches the end; `runCollect`, `runFold` and `runWith` over `Committed` do not compile, and each source's records end on their own one |

Every body runs with `kafka.topic`, `kafka.partition` and `kafka.offset` on
its log lines.

## Writing to Kafka

A `Producer` is one `KafkaProducer`, opened once and shared: the client is
thread-safe, and it batches what every caller sends. Whoever opens it closes
it, which sends what is pending.

```kotlin
import io.github.matthewjones372.lark.kafka.Kafka
import io.github.matthewjones372.lark.kafka.Topic
import io.github.matthewjones372.lark.kafka.deadLetters
import io.github.matthewjones372.lark.kafka.divertLefts
import io.github.matthewjones372.lark.kafka.producer
import io.github.matthewjones372.lark.kafka.publishRecord
import io.github.matthewjones372.lark.kafka.record
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer

val orders = Kafka.producer(producerProperties, key = StringSerializer(), value = orderSerializer)
val bytes = Kafka.producer(producerProperties, key = ByteArraySerializer(), value = ByteArraySerializer())

Kafka.consume(consumerProperties, Topic("carts"), key = Decoder.string(), value = carts)
    .divertLefts(bytes.deadLetters(Topic("carts.dead")))
    .mapRecord { cart -> cart.value().checkout() }
    .publishRecord(orders) { order -> Topic("orders").record(order.id, order) }
    .runCommitting()
```

- **`publishRecord` commits after the output.** Up to `inFlight` (256)
  records wait on the broker at once, so the producer fills its batches. Each
  element passes on in the order it came, once it and every one before it are
  acknowledged, so an offset is never committed ahead of what it produced. A
  stop or a restart sends the rest again: at-least-once.
- **`publishTo`** is the same on any stream, for a source that is not Kafka.
- **A record the producer gives up on is a defect**, for `restartOnDefect`.
  The client has already retried what it could, for up to
  `delivery.timeout.ms`. `producer.publish(record)` answers
  `Either<PublishFailed, Published>` for a caller that wants to route it.
- **`deadLetters(topic)`** is `divertLefts`' function: the unreadable record's
  bytes and headers, plus `lark.dead-letter.topic`, `.partition`, `.offset`,
  `.part` and `.cause`. It returns once the letter is acknowledged, so the bad
  record is never committed past a letter that was not written.

## Exactly once, in a transaction

`publishRecord` is at-least-once: a crash after an output is acknowledged
but before its offset is committed writes it again. `transacted` commits
the outputs and the offset in one Kafka transaction instead, so a reader
with `isolation.level=read_committed` sees each record's outputs once.

```kotlin
import io.github.matthewjones372.lark.kafka.transacted
import io.github.matthewjones372.lark.kafka.transactionalProducer

val orders = Kafka.transactionalProducer(producerProperties, transactionalId = "checkout-1", StringSerializer(), orderSerializer)

Kafka.consume(consumerProperties, Topic("carts"), key = StringDeserializer(), value = carts)
    .mapRecord { cart -> cart.value().checkout() }
    .transacted(orders) { order -> listOf(Topic("orders").record(order.id, order)) }
    .restartOnDefect(Schedule.exponential(100.milliseconds))
    .runFold(0L, Long::plus)
```

- **One transaction per batch.** Records are batched by `groupedWithin`, up
  to 500 or what arrives within 100 ms. Each batch is one transaction: every
  output is sent, then the batch's offsets with the consumer's group
  metadata, then the transaction commits. Transactions run one at a time,
  so a transaction holds exactly the outputs of the offsets it commits.
- **A failure aborts.** The transaction's outputs are never seen by a
  `read_committed` reader, and its offsets are not committed. It is a
  defect, so `restartOnDefect` after `transacted` reads again from the last
  committed offsets. `runTransactionally` is `transacted` run to its count,
  with no restart.
- **The consumer commits nothing** for these records; the transaction does.
  Only `Kafka.consume`'s records can be transacted: `Kafka.subscribe`'s carry
  no group metadata, and are refused.
- **The transactional id** is one per instance and stable across restarts,
  so a restarted instance fences the one it replaces.
- **A run's last transaction** commits after its consumer has left the
  group, with the group id alone. If the group rebalances in that moment, a
  new owner may read that last batch again. Spec 0124 says why.

## A registry that is down is not a bad record

A `Decoder` wraps any Kafka `Deserializer`, Avro and a schema registry
included, and is told which throws are transient. Those stay defects, so
`restartOnDefect` backs off and reads the same record again. Every other
throw is a `DecodeError` to divert or absolve. There is no default rule,
because the obvious one is wrong for Confluent's deserializer: a registry
answering 5xx throws a `RestClientException`, not an `IOException`, and a
rule that missed it would send every record to the dead-letter topic.

```kotlin
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.confluent.kafka.serializers.KafkaAvroDeserializer
import io.github.matthewjones372.lark.kafka.Decoder
import java.io.IOException

fun registryDown(thrown: Throwable): Boolean =
    generateSequence(thrown) { it.cause }
        .any { it is IOException || (it is RestClientException && it.status >= 500) }

fun orders(configured: KafkaAvroDeserializer): Decoder<Any> = Decoder(configured, transient = ::registryDown)
```
