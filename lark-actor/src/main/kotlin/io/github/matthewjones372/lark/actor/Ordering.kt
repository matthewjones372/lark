package io.github.matthewjones372.lark.actor

/** Which queued message a test runtime delivers next. */
sealed interface Ordering {
    /** First in, first out: each message in the order it was told. */
    data object Told : Ordering

    /**
     * A pick, by a random source seeded with [seed], among each sender's oldest message to each receiver: interleavings
     * vary from seed to seed but stay legal, since remoting keeps one sender's messages to one receiver in order.
     */
    data class Seeded(val seed: Long) : Ordering

    companion object {
        fun seeded(seed: Long): Ordering = Seeded(seed)
    }
}

/** What happened to one message in a test runtime, in the order it happened, to read back when a test fails. */
sealed interface TraceEntry {
    /** [message] reached [to]; [from] is the actor whose step told it, or null for the test itself. */
    data class Delivered(val from: Address?, val to: Address, val message: Any) : TraceEntry
}
