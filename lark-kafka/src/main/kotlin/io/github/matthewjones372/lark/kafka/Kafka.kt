package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.stream.Run
import io.github.matthewjones372.lark.stream.SourceSeam
import io.github.matthewjones372.lark.stream.Stream
import io.github.matthewjones372.lark.stream.hooked
import io.github.matthewjones372.lark.stream.mapConcat
import io.github.matthewjones372.lark.stream.runWith
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.pekko.Done
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerMessage
import org.apache.pekko.kafka.ConsumerSettings
import org.apache.pekko.kafka.Subscriptions
import org.apache.pekko.kafka.javadsl.Committer
import org.apache.pekko.kafka.javadsl.Consumer

/**
 * A topic by name, so a subscription's arguments cannot be mistaken for a group id or a server.
 *
 * A data class and not a value class: Kotlin refuses a value class as a vararg.
 */
data class Topic(val name: String)

object Kafka {

    /**
     * Every record on [topics] for the settings' group, each with the offset `runCommitting` commits.
     *
     * A run's `stop()` drains: the consumer stops fetching, and the records it already sent finish and are
     * committed before the exit completes. The consumer is shut down once the run has ended.
     */
    @OptIn(SourceSeam::class)
    fun <K, V> subscribe(
        settings: ConsumerSettings<K, V>,
        vararg topics: Topic,
    ): Stream<Nothing, Committed<ConsumerRecord<K, V>>> =
        Stream.hooked { hooks ->
            Consumer.committableSource(settings, Subscriptions.topics(topics.map { it.name }.toSet()))
                .mapMaterializedValue { control ->
                    hooks.onStop { control.stop() }
                    hooks.onEnd { control.shutdown() }
                }
                .map { message ->
                    val offset = message.committableOffset()
                    Committed(message.record(), offset.partitionOffset(), offset)
                }
        }
}

/** A run described that commits each element's offset once the element reaches the end of the stream. */
fun <E> Stream<E, Committed<*>>.runCommitting(settings: CommitterSettings): Run<E, Done> =
    mapConcat { listOfNotNull(it.offset) }.runWith(Committer.sink<ConsumerMessage.Committable>(settings))
