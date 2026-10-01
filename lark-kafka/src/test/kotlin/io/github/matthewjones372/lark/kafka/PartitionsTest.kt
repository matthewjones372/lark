package io.github.matthewjones372.lark.kafka

import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

class PartitionsTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()
    }

    @Test
    fun `a topic's partitions are listed in order, by a consumer that joins no group`() {
        kafka.create("clicks", partitions = 3)

        Kafka.partitions(mapOf(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap), Topic("clicks")) shouldBe
            listOf(0, 1, 2)
    }
}
