// lark-actor's journal followed as a lark-stream Stream (spec 0075): lark-actor and lark-stream, nothing else, and
// any backend runs it. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":lark-actor"))
    api(project(":lark-stream"))

    // The backend the tests run projections on.
    testImplementation(project(":lark-stream-forks"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.actor.projection.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
