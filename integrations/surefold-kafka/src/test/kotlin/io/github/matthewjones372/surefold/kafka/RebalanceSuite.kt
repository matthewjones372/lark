package io.github.matthewjones372.surefold.kafka

import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.junit.SharedSuite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.withClue
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.run
import io.github.matthewjones372.surefold.stream.start
import io.github.matthewjones372.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spec 0158: what a rebalance does to the records a consumer has handed out and not yet handled. */
object RebalanceSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-rebalance-test")

    private const val GENEROUS_SECONDS = 60L

    // Short, so a member whose stream is busy rejoins within seconds through its keeper's polls; at 2s, a Pekko member
    // is dropped from the group before its keeper's polls finish the rejoin (see spec 0158).
    private const val POLL_INTERVAL_MILLIS = 10_000
    private const val EACH = 3

    private fun strings(kit: KafkaKit, group: String, topic: Topic) = Kafka.consume(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kit.broker.bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to POLL_INTERVAL_MILLIS,
        ),
        topic,
        key = StringDeserializer(),
        value = StringDeserializer(),
    )

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    init {
        backends.forEach { on ->
            test("a member joining mid-stream reads again what the member it took a partition from had in hand [$on]") {
                    kit ->
                val topic = Topic("rebalanced-$on")
                val group = "rebalanced-$on"
                kit.broker.create(topic, 2)
                kit.broker.sendTo(topic, 0, "a1", "a2", "a3")
                kit.broker.sendTo(topic, 1, "b1", "b2", "b3")

                // The first member has every record in hand, and handles none until the second has read its share.
                val inHand = CountDownLatch(2 * EACH)
                val release = CountDownLatch(1)
                val first = ConcurrentLinkedQueue<String>()
                val running = strings(kit, group, topic)
                    .mapParRecord(2 * EACH) { record ->
                        inHand.countDown()
                        release.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                        record.value().also(first::add)
                    }
                    .take(2L * EACH)
                    .runCommitting()
                    .start(kit.backend(on))
                val handedOut = inHand.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

                // The group gives the second member one of the two partitions, which it reads from the last commit.
                val second = ConcurrentLinkedQueue<String>()
                val joined = strings(kit, group, topic)
                    .mapRecord { it.value().also(second::add) }
                    .take(EACH.toLong())
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled() shouldBe Exit.Done(EACH.toLong())
                release.countDown()
                val ended = running.exit.settled() shouldBe Exit.Done(2L * EACH)

                handedOut and joined and ended and
                    withClue("today, each record of the partition that moved is read twice") {
                        second.count { it in first } shouldBe EACH
                    }
            }
        }
    }
}
