// surefold-app on surefold-openfeature: an OpenFeature provider as a node, set before anything reads a flag and
// shut down on release. It adds nothing beyond the two, which NoOtherDependenciesTest asserts.
dependencies {
    api(project(":surefold-app"))
    api(project(":surefold-openfeature"))

    testImplementation(project(":surefold-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.openfeature.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
