package io.github.matthewjones372.lark.kafka

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.resource

/** A [KafkaBroker], started when it is acquired and stopped on release: the shared resource of a lark-junit suite. */
fun kafkaBroker(): Resource<KafkaBroker> = resource {
    install({ KafkaBroker().also { it.start() } }) { broker, _ -> broker.stop() }
}
