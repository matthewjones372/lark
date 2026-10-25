package io.github.matthewjones372.surefold.kafka

import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.resource

/**
 * A [KafkaBroker], started when it is acquired and stopped on release: the shared resource of a surefold-junit suite.
 */
fun kafkaBroker(): Resource<KafkaBroker> = resource {
    install({ KafkaBroker().also { it.start() } }) { broker, _ -> broker.stop() }
}
