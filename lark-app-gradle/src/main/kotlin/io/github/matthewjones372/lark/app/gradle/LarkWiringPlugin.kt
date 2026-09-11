package io.github.matthewjones372.lark.app.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

/**
 * Applying this is the whole of what an application does to get its graph checked.
 *
 * The check runs the graph rather than reading the source, which is why it sees an actor node and a
 * module assembled in a conditional — and why it is a task rather than a compiler plugin.
 */
class LarkWiringPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val wiring = project.extensions.create("larkWiring", LarkWiringExtension::class.java)
        wiring.failOn.convention("FAIL")
        wiring.diagrams.convention(project.layout.buildDirectory.dir("reports/lark"))

        // Registered against the Kotlin plugin rather than at apply time, so the order the two are
        // written in a build script does not decide whether there is a task.
        project.plugins.withId("org.jetbrains.kotlin.jvm") {
            register(project, wiring)
        }
    }

    private fun register(project: Project, wiring: LarkWiringExtension) {
        val main = project.extensions.getByType(SourceSetContainer::class.java).getByName("main")
        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        val toolchains = project.extensions.getByType(JavaToolchainService::class.java)

        val check = project.tasks.register(TASK, JavaExec::class.java) { exec ->
            exec.group = "verification"
            exec.description = "Checks every lark-app graph in this project, and renders each one."
            exec.mainClass.set(CHECKER)
            exec.javaLauncher.set(toolchains.launcherFor(java.toolchain))
            exec.classpath = main.runtimeClasspath
            exec.inputs.files(main.runtimeClasspath).withPropertyName("runtimeClasspath")
            exec.outputs.dir(wiring.diagrams)
            exec.argumentProviders.add(
                CommandLineArgumentProvider {
                    listOf(
                        main.output.classesDirs.joinToString(File.pathSeparator) { it.absolutePath },
                        wiring.diagrams.get().asFile.absolutePath,
                        wiring.failOn.get(),
                    )
                },
            )
        }

        project.tasks.named("check") { it.dependsOn(check) }
    }

    private companion object {
        const val TASK = "larkWiring"
        const val CHECKER = "io.github.matthewjones372.lark.app.CheckKt"
    }
}
