package io.github.matthewjones372.lark

import java.time.Instant
import kotlin.time.Duration

private const val NANOS_PER_MILLI = 1_000_000L

/** Time as a value: what a schedule waits on, and what a service reads the hour from. */
interface Clock {

    fun now(): Instant

    /** Interruptible, so a schedule ends where the JDK's only cancellation reaches it. */
    fun sleep(duration: Duration)
}

/** The wall clock, and the JDK's own sleep. */
object SystemClock : Clock {

    override fun now(): Instant = Instant.now()

    override fun sleep(duration: Duration) {
        val nanos = duration.inWholeNanoseconds
        Thread.sleep(nanos / NANOS_PER_MILLI, (nanos % NANOS_PER_MILLI).toInt())
    }
}

/** A clock that does not move and does not wait, which is what most tests of a backoff want. */
fun fixedClock(at: Instant = Instant.EPOCH): Clock = object : Clock {

    override fun now(): Instant = at

    override fun sleep(duration: Duration) = Unit
}

/** The clock a schedule waits on, and the one a fork inherits from its opener. */
val clock: LarkLocal<Clock> = larkLocal { SystemClock }
