// A backend for tests: every stage on the calling thread, and time a test owns (spec 0048). It is the
// Forks pull loop run in place, so it depends on lark-stream-forks and on nothing else.
// NoOtherDependenciesTest asserts that, on the classpath a consumer gets.

dependencies {
    api(project(":lark-stream-forks"))
}

tasks.test {
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

// A backend answers to the SPI's key and runs, which is what the opt-in is for.
kotlin {
    compilerOptions {
        optIn.add("io.github.matthewjones372.lark.stream.StreamSpi")
    }
}
