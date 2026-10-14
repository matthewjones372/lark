// lark-stream on lark's own forks: a run is a pull loop on one virtual thread, and nothing is under it
// but lark-stream. NoOtherDependenciesTest asserts exactly that, on the classpath a consumer gets.

dependencies {
    api(project(":lark-stream"))
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.stream.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

// A backend reads the node tree, which is what the opt-in is for.
kotlin {
    compilerOptions {
        optIn.add("io.github.matthewjones372.lark.stream.StreamSpi")
    }
}
