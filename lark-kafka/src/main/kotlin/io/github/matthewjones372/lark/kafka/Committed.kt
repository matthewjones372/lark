@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.logAnnotated

/** For a module that brings records of its own into lark-kafka, such as the Pekko connector; not for a pipeline. */
@RequiresOptIn("A seam for a module that feeds lark-kafka records from a consumer of its own.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION)
annotation class KafkaSpi

/** An element and the offset it came with; the offset is committed by `runCommitting` and never handed out. */
class Committed<out A : Any> @KafkaSpi constructor(
    val value: A,
    @property:KafkaSpi val position: Position,
    // Null on every element but the last that one record expanded to, so the record commits once, after all of them.
    @property:KafkaSpi val handle: Handle?,
)

/** Where a record was read from: what every line a body writes is annotated with. */
@KafkaSpi
data class Position(val topic: String, val partition: Int, val offset: Long)

/** How a record's offset is committed, which depends on the consumer that read it. */
@KafkaSpi
interface Handle {

    /** The record is done with: [Kafka.consume]'s loop commits it before its next poll. */
    fun handled()
}

internal fun <B : Any> Committed<*>.carrying(value: B): Committed<B> = Committed(value, position, handle)

/** [f] on the value, with the record's position on every line it writes. */
internal fun <A : Any, B> Committed<A>.annotated(f: (A) -> B): B =
    logAnnotated(
        "kafka.topic" to position.topic,
        "kafka.partition" to position.partition.toString(),
        "kafka.offset" to position.offset.toString(),
    ) { f(value) }
