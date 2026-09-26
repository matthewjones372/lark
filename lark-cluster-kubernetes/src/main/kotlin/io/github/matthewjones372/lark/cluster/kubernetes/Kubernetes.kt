package io.github.matthewjones372.lark.cluster.kubernetes

import io.fabric8.kubernetes.api.model.coordination.v1.LeaseBuilder
import io.fabric8.kubernetes.api.model.coordination.v1.LeaseSpecBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientException
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.clock
import io.github.matthewjones372.lark.cluster.Discovery
import io.github.matthewjones372.lark.cluster.Lease
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** A cluster on Kubernetes: its seeds from the pods API, and its even splits broken by a `Lease` object. */
object Kubernetes {

    /**
     * The pods in [namespace] labelled [selector] that are running, have an address and are not being deleted, each
     * at [port]. An API server that cannot be asked gives no seeds, as a DNS name that does not resolve does.
     */
    fun discovery(client: KubernetesClient, namespace: String, selector: Map<String, String>, port: Int): Discovery =
        Discovery {
            try {
                client.pods().inNamespace(namespace).withLabels(selector).list().items
                    .filter { it.status?.phase == "Running" && it.metadata?.deletionTimestamp == null }
                    .mapNotNull { pod -> pod.status?.podIP?.takeIf { it.isNotEmpty() } }
                    .sorted()
                    .map { Node("", it, port) }
            } catch (_: KubernetesClientException) {
                emptyList()
            }
        }

    /**
     * The `Lease` named [name] in [namespace]: a holder has it for [holdFor] from when it last acquired it, and every
     * acquire by the holder renews it. A write that lost a race to another holder is refused by the API server, since
     * it carries the version it read, and answers false.
     */
    fun lease(client: KubernetesClient, namespace: String, name: String, holdFor: Duration = 15.seconds): Lease =
        KubernetesLease(client, namespace, name, holdFor) { clock.get().now() }
}

internal class KubernetesLease(
    private val client: KubernetesClient,
    private val namespace: String,
    private val name: String,
    private val holdFor: Duration,
    private val now: () -> Instant,
) : Lease {

    override fun acquire(holder: String): Boolean = try {
        take(holder, ZonedDateTime.ofInstant(now(), ZoneOffset.UTC))
    } catch (_: KubernetesClientException) {
        false
    }

    private fun take(holder: String, at: ZonedDateTime): Boolean {
        val leases = client.leases().inNamespace(namespace)
        val seconds = holdFor.inWholeSeconds.toInt()
        val existing = leases.withName(name).get()
        if (existing == null) {
            val spec = LeaseSpecBuilder().withHolderIdentity(holder).withLeaseDurationSeconds(seconds)
                .withAcquireTime(at).withRenewTime(at).withLeaseTransitions(0).build()
            val lease = LeaseBuilder().withNewMetadata().withName(name).endMetadata().withSpec(spec).build()
            leases.resource(lease).create()
            return true
        }
        val spec = existing.spec
        val held = spec.holderIdentity
        val until = spec.renewTime?.plusSeconds((spec.leaseDurationSeconds ?: 0).toLong())
        val heldByAnother = held != null && held != holder
        if (heldByAnother && until?.isAfter(at) == true) return false
        val next = LeaseSpecBuilder(spec).withHolderIdentity(holder).withLeaseDurationSeconds(seconds).withRenewTime(at)
        if (held != holder) next.withAcquireTime(at).withLeaseTransitions((spec.leaseTransitions ?: 0) + 1)
        existing.spec = next.build()
        leases.resource(existing).update()
        return true
    }
}
