// lark-stream on lark-actor (spec 0066): a run is an actor that pulls Forks' loop a batch at a time.
// NoOtherDependenciesTest asserts the runtime classpath is lark-stream-forks and lark-actor, and nothing else.

dependencies {
    api(project(":lark-stream-forks"))
    api(project(":lark-actor"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.stream.actors.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

// A backend reads the node tree and drives Forks' loop, which is what the opt-in is for.
kotlin {
    compilerOptions {
        optIn.add("io.github.matthewjones372.lark.stream.StreamSpi")
    }
}
