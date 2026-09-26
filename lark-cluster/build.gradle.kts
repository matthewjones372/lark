// lark-actor as a cluster (spec 0069): discovery, gossip membership and downing, on lark-actor-remote.
// NoOtherDependenciesTest asserts the runtime classpath is lark-actor-remote and what it brings, and nothing else.

dependencies {
    api(project(":lark-actor-remote"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.cluster.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
