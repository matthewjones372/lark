package io.github.matthewjones372.surefold.kafka

import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.left
import io.github.matthewjones372.surefold.fail.map
import io.github.matthewjones372.surefold.fail.right
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.NewPartitions
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.TopicDescription
import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import java.util.Optional
import java.util.concurrent.ExecutionException

/**
 * The few `Admin` calls a service's start-up and a team's runbooks make (spec 0162), each blocking until the cluster
 * answers. The failures a caller acts on are values; anything else the cluster answers is thrown, as a defect.
 * Whoever opens it closes it.
 */
class KafkaAdmin internal constructor(private val admin: Admin) : AutoCloseable {

    /**
     * [topic] made to exist with at least [partitions] partitions: created with them, [replicas] copies of each (the
     * broker's default when null) and [config], or grown to them if it has fewer. It never shrinks a topic, which
     * Kafka cannot do, and refuses to try. An existing topic's config is left as it is; how it differs from [config]
     * comes back for the caller to act on.
     */
    fun ensure(
        topic: Topic,
        partitions: Int,
        replicas: Int? = null,
        config: Map<String, String> = emptyMap(),
    ): Ensured {
        require(partitions > 0) { "a topic needs a partition, and ${topic.name} was asked for $partitions" }
        val created = try {
            val asked = NewTopic(topic.name, Optional.of(partitions), Optional.ofNullable(replicas?.toShort()))
            admin.createTopics(listOf(asked.configs(config))).all().answer()
            true
        } catch (_: TopicExistsException) {
            false
        }
        if (created) return Ensured(created = true, grownFrom = null, differing = emptyMap())

        val described = admin.describeTopics(listOf(topic.name)).allTopicNames().answer()
        val had = described.getValue(topic.name).partitions().size
        require(had <= partitions) { "${topic.name} has $had partitions, and Kafka cannot shrink it to $partitions" }
        if (had < partitions) {
            admin.createPartitions(mapOf(topic.name to NewPartitions.increaseTo(partitions))).all().answer()
        }
        val grownFrom = had.takeIf { it < partitions }
        return Ensured(created = false, grownFrom = grownFrom, differing = differing(topic, config))
    }

    /** Every topic on the cluster but Kafka's own internal ones. */
    fun topics(): Set<Topic> = admin.listTopics().names().answer().map(::Topic).toSet()

    /** [topic]'s partitions, leaders and replicas. */
    fun describe(topic: Topic): Either<NoSuchTopic, TopicDescription> = existing(topic) {
        admin.describeTopics(listOf(topic.name)).allTopicNames().answer().getValue(topic.name)
    }

    /** [topic] deleted, records and all. */
    fun delete(topic: Topic): Either<NoSuchTopic, Unit> = existing(topic) {
        admin.deleteTopics(listOf(topic.name)).all().answer()
    }.map { }

    override fun close() = admin.close()

    /** Each key of [config] whose value on [topic] is not the one asked, with the topic's own value. */
    private fun differing(topic: Topic, config: Map<String, String>): Map<String, String?> {
        if (config.isEmpty()) return emptyMap()
        val resource = ConfigResource(ConfigResource.Type.TOPIC, topic.name)
        val own = admin.describeConfigs(listOf(resource)).all().answer().getValue(resource)
        return config.mapValues { (key, _) -> own.get(key)?.value() }.filter { (key, value) -> value != config[key] }
    }

    private fun <A> existing(topic: Topic, block: () -> A): Either<NoSuchTopic, A> = try {
        block().right()
    } catch (_: UnknownTopicOrPartitionException) {
        NoSuchTopic(topic).left()
    }
}

/** An admin client on the cluster [properties] name, for the caller to close. */
fun Kafka.admin(properties: Map<String, Any>): KafkaAdmin = KafkaAdmin(Admin.create(properties))

/** What [KafkaAdmin.ensure] did: created the topic, grew it from [grownFrom] partitions, or neither. */
data class Ensured(val created: Boolean, val grownFrom: Int?, val differing: Map<String, String?>)

/** A topic the cluster does not have. */
data class NoSuchTopic(val topic: Topic)

/** The cluster's answer, or what it failed with, unwrapped from the future's `ExecutionException`. */
private fun <A> KafkaFuture<A>.answer(): A = try {
    get()
} catch (failed: ExecutionException) {
    throw failed.cause ?: failed
}
