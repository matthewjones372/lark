package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig

object PartitionsSuite : Suite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    init {
        test("a topic's partitions are listed in order, by a consumer that joins no group") { kafka ->
            kafka.create("clicks", partitions = 3)

            Kafka.partitions(mapOf(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap), Topic("clicks")) shouldBe
                listOf(0, 1, 2)
        }
    }
}
