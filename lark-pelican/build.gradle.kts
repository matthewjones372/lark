// Pelican handlers written in Arrow's Raise, each on a virtual thread of its
// own. `lark` plus `pelican-arrow` and nothing else, which is what
// NoOtherDependenciesTest asserts: a blocking port stays blocking, and no
// second effect system arrives with the binder.
dependencies {
    api(project(":lark"))
    api("io.github.matthewjones372:pelican-arrow:1.0.0-RC1")

    // Test only, so that the contract tests can run an `Api` in memory through
    // Pelican's typed test client: a real backend and a real codec, neither of
    // which a consumer of this module inherits.
    testImplementation("io.github.matthewjones372:pelican-test:1.0.0-RC1")
    testImplementation("io.github.matthewjones372:pelican-test-pekko:1.0.0-RC1")
    testImplementation("io.github.matthewjones372:pelican-jackson:1.0.0-RC1")
    testImplementation("io.github.matthewjones372:pelican-pekko:1.0.0-RC1")
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.pelican.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
