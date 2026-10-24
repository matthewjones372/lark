// The OpenFeature SDK, with its evaluation context bound where a fork and a message can see it.
// It adds the SDK and the slf4j-api the SDK logs through, which NoOtherDependenciesTest asserts.
dependencies {
    api(project(":lark"))
    api("dev.openfeature:sdk:1.23.0")

    // Actors to send to, for the context carried across a message.
    testImplementation(project(":lark-actor"))
    testImplementation(project(":lark-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.openfeature.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
