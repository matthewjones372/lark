// A bank you can watch (spec 0094): accounts and transfers sharded across three nodes. An application, never
// published. NoOtherDependenciesTest asserts the runtime classpath is exactly what is declared here.

plugins {
    application
}

dependencies {
    implementation(project(":lark-cluster"))
    implementation(project(":lark-actor-journal-jdbc"))
    // The journal in memory by default, or on Postgres with `--jdbc`.
    implementation("com.h2database:h2:2.3.232")
    implementation("org.postgresql:postgresql:42.7.7")

    // A real Postgres for `--jdbc`'s test: a binary from Maven Central, run by the test JVM, no Docker.
    testImplementation("io.zonky.test:embedded-postgres:2.1.0")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.5.0"))
}

application {
    mainClass.set("io.github.matthewjones372.lark.bank.MainKt")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf("-Dlark.bank.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name })
        },
    )
}
