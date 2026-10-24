package io.github.matthewjones372.lark.cluster.kubernetes

import io.github.matthewjones372.lark.assert.shouldBeEmpty
import io.github.matthewjones372.lark.assert.shouldNotBeNull
import io.github.matthewjones372.lark.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What lark-cluster-kubernetes may put on a service's classpath: lark-cluster's own, and the fabric8 client. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "lark",
        // The fabric8 client, its model, and the JSON and YAML it reads them with.
        "kubernetes-",
        "zjsonpatch-",
        "jackson-",
        "snakeyaml-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is lark-cluster and the fabric8 client, and nothing else`() {
        val raw = System.getProperty("lark.cluster.kubernetes.runtimeClasspath")
        withClue("the build must pass -Dlark.cluster.kubernetes.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("lark-cluster-kubernetes must stay lark-cluster and the fabric8 client, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
