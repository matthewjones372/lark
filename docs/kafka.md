# lark-kafka

**A Kafka record is committed only after the work on it is done.** A
subscription is a `Stream` of `Committed` elements, each carrying its record's
offset, and the only way to run one is `runCommitting`. Forgetting to commit,
or committing before the work, does not compile.

Everything below is in `io.github.matthewjones372.lark.kafka`. Specs
[0053](../specs/0053-a-record-that-commits-after-it-is-handled.md),
[0054](../specs/0054-a-record-that-fails-to-decode.md) and
[0056](../specs/0056-kafka-on-any-backend.md) give the reasons.

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
    implementation("io.github.matthewjones372:lark-kafka:0.6.0")
    // Or Pekko's connector: lark-kafka, lark-stream-pekko and pekko-connectors-kafka come with it.
    implementation("io.github.matthewjones372:lark-kafka-pekko:0.6.0")
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
| `runCommitting()` / `runCommitting(settings)` | the run, committing each offset once its record's element reaches the end; `runCollect`, `runFold` and `runWith` over `Committed` do not compile, and each source's records end on their own one |

Every body runs with `kafka.topic`, `kafka.partition` and `kafka.offset` on
its log lines.

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
