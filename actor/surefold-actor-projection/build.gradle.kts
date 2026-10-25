// surefold-actor's journal followed as a surefold-stream Stream (spec 0075): surefold-actor and surefold-stream,
// nothing else, and any backend runs it. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-actor"))
    api(project(":surefold-stream"))

    // The backend the tests run projections on.
    testImplementation(project(":surefold-stream-forks"))
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.actor.projection.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
