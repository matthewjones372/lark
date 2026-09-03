// Arrow's Raise, forked and awaited on virtual threads. Arrow and the JDK and
// nothing else, which is what NoOtherDependenciesTest asserts: a fork answers
// with an Either, so nothing here needs to know what is being served.
dependencies {
    api("io.arrow-kt:arrow-core:2.1.2")
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
