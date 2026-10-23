// Lark's own assertions (spec 0169). It depends on lark, for Either, and on nothing else: a failed check is an
// AssertionError, which every test runner reports. NoOtherDependenciesTest asserts that.
dependencies {
    api(project(":lark"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.assert.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
