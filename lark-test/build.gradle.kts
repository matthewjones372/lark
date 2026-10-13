// For tests of a lark service (spec 0115). It depends on lark and nothing else: a failed step is an
// AssertionError, so any assertion library works inside one without this module choosing it.
// NoOtherDependenciesTest asserts that, on the classpath a consumer gets.

dependencies {
    api(project(":lark"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.test.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
