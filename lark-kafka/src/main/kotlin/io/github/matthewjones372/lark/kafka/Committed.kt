package io.github.matthewjones372.lark.kafka

import org.apache.pekko.kafka.ConsumerMessage

/** An element and the offset it came with; the offset is committed by `runCommitting` and never handed out. */
class Committed<out A : Any> internal constructor(
    val value: A,
    internal val offset: ConsumerMessage.Committable,
)
