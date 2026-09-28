package io.github.matthewjones372.lark.cluster.kubernetes

import io.fabric8.kubernetes.api.model.PodBuilder
import io.fabric8.kubernetes.client.ConfigBuilder
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.KubernetesClientBuilder
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.cluster.Joins
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@EnableKubernetesMockClient(crud = true)
class KubernetesTest {

    lateinit var client: KubernetesClient

    private var at = Instant.parse("2026-09-26T10:00:00Z")

    private fun pod(name: String, ip: String?, phase: String, app: String = "orders") {
        val pod = PodBuilder()
            .withNewMetadata().withName(name).withNamespace("shop").addToLabels("app", app).endMetadata()
            .withNewStatus().withPhase(phase).withPodIP(ip).endStatus()
            .build()
        client.pods().inNamespace("shop").resource(pod).create()
    }

    private fun lease() = KubernetesLease(client, "shop", "orders", 15.seconds) { at }

    @Test
    fun `the running pods with the service's labels and an address are the seeds, at the cluster's port`() {
        pod("orders-1", "10.1.0.7", "Running")
        pod("orders-0", "10.1.0.5", "Running")
        pod("orders-2", null, "Pending")
        pod("orders-3", "10.1.0.9", "Succeeded")
        pod("billing-0", "10.1.0.6", "Running", app = "billing")

        Kubernetes.discovery(client, "shop", mapOf("app" to "orders"), 25520).seeds() shouldContainExactly
            listOf(Node("", "10.1.0.5", 25520), Node("", "10.1.0.7", 25520))
    }

    @Test
    fun `an API server that cannot be asked gives no seeds, and no lease`() {
        val nowhere = ServerSocket(0).use { it.localPort }
        val config = ConfigBuilder().withMasterUrl("http://127.0.0.1:$nowhere").withRequestRetryBackoffLimit(0).build()
        KubernetesClientBuilder().withConfig(config).build().use { unreachable ->
            Kubernetes.discovery(unreachable, "shop", mapOf("app" to "orders"), 25520).seeds().shouldBeEmpty()
            Kubernetes.lease(unreachable, "shop", "orders").acquire("orders-0") shouldBe false
        }
    }

    @Test
    fun `the first to ask holds the lease, and asking again renews it for the same holder only`() {
        val lease = lease()

        lease.acquire("10.1.0.5") shouldBe true
        at = at.plusSeconds(10)
        lease.acquire("10.1.0.5") shouldBe true
        lease.acquire("10.1.0.9") shouldBe false

        val held = client.leases().inNamespace("shop").withName("orders").get().spec
        held.holderIdentity shouldBe "10.1.0.5"
        held.renewTime.toInstant() shouldBe at
        held.leaseTransitions shouldBe 0
    }

    @Test
    fun `a lease its holder stopped renewing passes to the next to ask`() {
        val lease = lease()
        lease.acquire("10.1.0.5") shouldBe true

        at = at.plusSeconds(16)

        lease.acquire("10.1.0.9") shouldBe true
        lease.acquire("10.1.0.5") shouldBe false
        client.leases().inNamespace("shop").withName("orders").get().spec.leaseTransitions shouldBe 1
    }

    @Test
    fun `a joining finds the pods and takes the lease through one client, and closing it closes the client`() {
        pod("orders-0", "10.1.0.5", "Running")
        var own: KubernetesClient? = null
        val joining = Kubernetes.joining(
            { KubernetesClientBuilder().withConfig(client.configuration).build().also { own = it } },
            mapOf("app" to "orders"), 25520, "orders", "shop", 15.seconds, 20.seconds,
        )
        joining.use {
            it.discovery.seeds() shouldContainExactly listOf(Node("", "10.1.0.5", 25520))
            Kubernetes.lease(own!!, "shop", "orders").acquire("10.1.0.5") shouldBe true
        }
        shouldThrow<IllegalStateException> { own!!.pods().inNamespace("shop").list() }
    }

    @Test
    fun `the namespace is the service account's, else POD_NAMESPACE, else refused`(@TempDir dir: Path) {
        val file = dir.resolve("namespace")
        Kubernetes.ownNamespace(file) { "from-env" } shouldBe "from-env"
        Files.writeString(file, "shop\n")
        Kubernetes.ownNamespace(file) { "from-env" } shouldBe "shop"
        shouldThrow<IllegalArgumentException> { Kubernetes.ownNamespace(dir.resolve("none")) { null } }
    }

    @Test
    fun `join = kubernetes is found by its name`() {
        Joins.available()["kubernetes"].shouldBeInstanceOf<KubernetesJoins>()
    }
}
