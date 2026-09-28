// lark-actor's Journal on JDBC (spec 0072): the JDK's java.sql and nothing else. No driver and no pool: the
// DataSource is the service's. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":lark-actor"))

    testImplementation(testFixtures(project(":lark-actor")))
    testImplementation("com.h2database:h2:2.3.232")
    // The changelogs are applied as a service applies them. Tests only: the module still needs nothing at runtime.
    testImplementation("org.liquibase:liquibase-core:4.32.0")
    // A real Postgres for the contracts (spec 0078): a binary from Maven Central, run by the test JVM, no Docker.
    testImplementation("io.zonky.test:embedded-postgres:2.1.0")
    testImplementation(platform("io.zonky.test.postgres:embedded-postgres-binaries-bom:17.5.0"))
    testImplementation("org.postgresql:postgresql:42.7.7")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.actor.journal.jdbc.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
