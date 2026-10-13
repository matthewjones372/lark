// lark-app on lark-cluster: a cluster as a node, joined as config says. The backend is found by name on the
// classpath, so this module brings neither fabric8 nor the AWS SDK; the application adds the one it joins through.
// NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":lark-app-actor"))
    api(project(":lark-app-typesafe"))
    api(project(":lark-cluster"))
}

// Read at configuration time, so the configuration cache survives it.
val repoRoot = rootProject.projectDir.absolutePath
val guide = files(rootProject.layout.projectDirectory.file("docs/cluster.md"))

tasks.test {
    inputs.files(guide).withPropertyName("guide")
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.app.cluster.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dlark.app.cluster.repoRoot=$repoRoot",
            )
        },
    )
}
