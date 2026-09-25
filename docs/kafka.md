# lark-kafka

**A Kafka record is committed only after the work on it is done.** A
subscription is a `Stream` of `Committed` elements, each carrying its record's
offset, and the only way to run one is `runCommitting`. Forgetting to commit,
or committing before the work, does not compile.

The module is `lark-kafka`, over Pekko's own Kafka connector, and everything
below is in `io.github.matthewjones372.lark.kafka`. Specs
[0052](../specs/0052-a-record-that-commits-after-it-is-handled.md) and
[0053](../specs/0053-a-record-that-fails-to-decode.md) give the reasons.

```kotlin
dependencies {
    // lark-stream-pekko, Pekko's Kafka connector and kafka-clients come with it; nothing else does
    implementation("io.github.matthewjones372:lark-kafka:0.6.0")
}
```

## Operators

| Call | Answers |
|---|---|
| `Kafka.subscribe(settings: ConsumerSettings<K, V>, vararg topics: Topic)` | `Stream<Nothing, Committed<ConsumerRecord<K, V>>>`, for deserializers that cannot fail |
| `Kafka.subscribe(settings: ConsumerSettings<ByteArray?, ByteArray?>, vararg topics, key: Decoder<K>, value: Decoder<V>)` | the same with each record decoded in the stream: `Committed<Either<DecodeError, ConsumerRecord<K, V>>>` |
| `mapRecord`, `mapRecordOrFail`, `mapParRecord`, `mapParRecordOrFail`, `filterRecord`, `mapConcatRecord` | lark-stream's operator of the same stem, with the body on the value and the offset carried. `Record` because the same names beside lark-stream's would be ambiguous |
| `divertLefts(to: (L) -> Unit)` | each `Left` to a function, in order, before anything after it moves on; a throw from it is a defect and the record is not committed |
| `absolve()` | the first `Left` ends the run `Failed` |
| `runCommitting(settings: CommitterSettings): Run<E, Done>` | the run, committing each offset once its record's element reaches the end; `runCollect`, `runFold` and `runWith` over `Committed` do not compile |

Every body runs with `kafka.topic`, `kafka.partition` and `kafka.offset` on
its log lines. A started run's `stop()` drains: the consumer stops fetching,
what it already sent finishes and is committed, and then the exit completes.

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
