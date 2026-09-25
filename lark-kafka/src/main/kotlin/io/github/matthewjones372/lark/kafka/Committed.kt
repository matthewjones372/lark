package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.logAnnotated
import org.apache.pekko.kafka.ConsumerMessage

/** An element and the offset it came with; the offset is committed by `runCommitting` and never handed out. */
class Committed<out A : Any> internal constructor(
    val value: A,
    internal val position: ConsumerMessage.PartitionOffset,
    // Null on every element but the last that one record expanded to, so the record commits once, after all of them.
    internal val offset: ConsumerMessage.Committable?,
)

internal fun <B : Any> Committed<*>.carrying(value: B): Committed<B> = Committed(value, position, offset)

/** [f] on the value, with the record's position on every line it writes. */
internal fun <A : Any, B> Committed<A>.annotated(f: (A) -> B): B =
    logAnnotated(
        "kafka.topic" to position.key().topic(),
        "kafka.partition" to position.key().partition().toString(),
        "kafka.offset" to position.offset().toString(),
    ) { f(value) }
