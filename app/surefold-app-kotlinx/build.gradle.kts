// A HOCON section read from its @Serializable class (spec 0153): the descriptor the serialization plugin
// generates stands in for the reader block. NoOtherDependenciesTest asserts the runtime classpath.

plugins {
    kotlin("plugin.serialization")
}

dependencies {
    api(project(":surefold-app-typesafe"))
    api("org.jetbrains.kotlinx:kotlinx-serialization-core:1.11.0")
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.kotlinx.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
