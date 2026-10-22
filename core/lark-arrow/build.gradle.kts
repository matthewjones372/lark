// Lark's Either, NonEmptyList and Fail, converted to and from Arrow's, for a codebase already on Arrow.
// It adds arrow-core and nothing else, which NoOtherDependenciesTest asserts.
dependencies {
    api(project(":lark"))
    api("io.arrow-kt:arrow-core:2.1.2")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.arrow.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
