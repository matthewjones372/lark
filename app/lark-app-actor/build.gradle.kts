// lark-app on lark-actor: a flock as a node, and an actor as a node keyed by the ref of its protocol.
// NoOtherDependenciesTest asserts the runtime classpath is lark-app and lark-actor, and nothing else.

dependencies {
    api(project(":lark-app"))
    api(project(":lark-actor"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.app.actor.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

dependencies {
    // A cluster made in the actors' flock, to show it leaves as the application is released (spec 0080).
    testImplementation(project(":lark-cluster"))
}
