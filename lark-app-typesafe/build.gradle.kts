// Typesafe Config itself, not a view of it. Everything a HOCON file can say
// stays sayable: substitution, merging, lists, objects, durations, memory sizes.
val typesafeVersion = "1.4.3"

dependencies {
    api(project(":lark-app"))
    api("com.typesafe:config:$typesafeVersion")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.app.typesafe.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
