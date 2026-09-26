// An actor as a value and a step, on lark and the Arrow that arrives with it, and nothing else.
// NoOtherDependenciesTest asserts exactly that, on the classpath a consumer gets.

plugins {
    // JournalContract: the tests every journal runs, this module's own and any a service writes.
    `java-test-fixtures`
}

dependencies {
    api(project(":lark"))

    testFixturesApi("org.junit.jupiter:junit-jupiter-api:6.1.3")
    testFixturesImplementation("io.kotest:kotest-assertions-core:6.2.4")
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.actor.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

// The module's own rules on top of the shared ones: its tests never wait on time. See the file for why.
extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
    config.from(file("detekt.yml"))
}

tasks.named("check") { dependsOn("detektTestFixtures") }
