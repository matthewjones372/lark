// lark-actor across nodes (spec 0068): codecs a user owns, and one TCP connection per pair of nodes on JDK sockets.
// NoOtherDependenciesTest asserts the runtime classpath is lark-actor and what it brings, and nothing else.

plugins {
    // TestCertificates: the key stores lark's TLS tests use, this module's and lark-cluster's (spec 0073).
    `java-test-fixtures`
}

dependencies {
    api(project(":lark-actor"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.actor.remote.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

tasks.named("check") { dependsOn("detektTestFixtures") }
