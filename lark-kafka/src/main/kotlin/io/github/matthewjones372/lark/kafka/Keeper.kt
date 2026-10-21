package io.github.matthewjones372.lark.kafka

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Keeps a group's member alive while the stream behind it is slow (spec 0157). The broker drops a member that has
 * not polled for `max.poll.interval.ms`, and a run's consumer polls only when the stream asks for a record. Once half
 * that interval has passed without a poll, the keeper pauses every assigned partition and polls anyway: nothing is
 * read ahead, and the member stays. The stream's own next poll resumes them.
 *
 * Every touch of the consumer is under [lock], so it is never used by two threads at once, as the client requires.
 * What the keeper's poll returns, a partition assigned in a rebalance before it could be paused, goes to [keep].
 */
internal class Keeper<K, V>(
    private val consumer: KafkaConsumer<K, V>,
    properties: Map<String, Any>,
    private val keep: (Iterable<ConsumerRecord<K, V>>) -> Unit,
) {

    private val lock = ReentrantLock()

    /** Half the interval the group allows between polls: the keeper polls once this has passed without one. */
    private val within: Duration = intervalOf(properties).dividedBy(2)

    @Volatile
    private var polledAt = System.nanoTime()

    /** Held for the keeper's own poll, so a rebalance it sets off pauses the partitions it assigns. */
    @Volatile
    private var keeping = false

    /** What the keeper's poll threw, for the stream's next touch to throw where a poll of its own would have. */
    @Volatile
    private var failed: RuntimeException? = null

    private val stopped = CountDownLatch(1)

    private val thread = Thread.ofVirtual().name("lark-kafka-keeper").start(::run)

    /** [block] with the consumer to itself; a poll inside it counts, and resumes what the keeper paused. */
    fun <T> touching(block: () -> T): T = lock.withLock {
        failed?.let { thrown ->
            failed = null
            throw thrown
        }
        block()
    }

    /**
     * [block], the consumer's last use, whatever the keeper's poll threw: the commit and close are not skipped for
     * it. What it threw is thrown after, as the stream's next touch would have.
     */
    fun closing(block: () -> Unit) = lock.withLock {
        val pending = failed
        failed = null
        block()
        pending?.let { thrown -> throw thrown }
    }

    /** Before the stream's own poll: everything the keeper paused is read again. */
    fun polling() {
        consumer.resume(consumer.paused())
        polledAt = System.nanoTime()
    }

    /** Newly assigned [partitions], paused if the keeper's poll is the one assigning them. */
    fun assigned(partitions: Collection<TopicPartition>) {
        if (keeping) consumer.pause(partitions)
    }

    /**
     * Stops the keeper and waits for it, whatever interrupts the wait: a take that ends a Forks run closes the source
     * on an interrupted thread, and a keeper still polling must be done with the consumer before the close commits.
     */
    fun stop() {
        stopped.countDown()
        var interrupted = false
        while (thread.isAlive) {
            try {
                thread.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun run() {
        while (!stopped.await(within.toNanos() / CHECKS_PER_HALF, TimeUnit.NANOSECONDS)) {
            if (System.nanoTime() - polledAt >= within.toNanos() && lock.tryLock()) {
                try {
                    keepAlive()
                } finally {
                    lock.unlock()
                }
            }
        }
    }

    // Whatever the poll threw is the stream's to see, at its next touch; a wake meant for the stream is re-sent.
    @Suppress("TooGenericExceptionCaught")
    private fun keepAlive() {
        keeping = true
        try {
            consumer.pause(consumer.assignment())
            keep(consumer.poll(Duration.ZERO))
            polledAt = System.nanoTime()
        } catch (_: WakeupException) {
            consumer.wakeup()
        } catch (thrown: RuntimeException) {
            failed = thrown
        } finally {
            keeping = false
        }
    }

    private companion object {
        /** How often the keeper looks, per half interval: often enough to poll well inside it. */
        const val CHECKS_PER_HALF = 4L

        val DEFAULT_INTERVAL: Duration = Duration.ofMinutes(5)

        fun intervalOf(properties: Map<String, Any>): Duration =
            properties[ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG]?.toString()?.trim()?.toLongOrNull()
                ?.let(Duration::ofMillis) ?: DEFAULT_INTERVAL
    }
}
