// lark-app on lark-openfeature: an OpenFeature provider as a node, set before anything reads a flag and
// shut down on release. It adds nothing beyond the two, which NoOtherDependenciesTest asserts.
dependencies {
    api(project(":lark-app"))
    api(project(":lark-openfeature"))

    testImplementation(project(":lark-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.app.openfeature.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
