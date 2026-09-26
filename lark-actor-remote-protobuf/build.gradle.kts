// Messages of lark-actor-remote in Protobuf (spec 0071): a codec for a generated message, for a protocol of several,
// and for an ask whose request is one. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":lark-actor-remote"))
    api("com.google.protobuf:protobuf-java:4.36.2")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.actor.remote.protobuf.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
