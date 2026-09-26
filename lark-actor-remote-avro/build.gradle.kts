// Messages of lark-actor-remote in Avro (spec 0071): a codec for a specific record, resolved across its versions,
// and for an ask whose request is one. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":lark-actor-remote"))
    api("org.apache.avro:avro:1.12.2")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.actor.remote.avro.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
