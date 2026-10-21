// lark-actor's Journal on JDBC (spec 0072): the JDK's java.sql and nothing else. No driver and no pool: the
// DataSource is the service's. NoOtherDependenciesTest asserts the runtime classpath.

plugins {
    // Postgres: a real one in a container, with the changelog applied, for any module testing on this journal.
    `java-test-fixtures`
}

dependencies {
    api(project(":lark-actor"))

    testFixturesApi("org.postgresql:postgresql:42.7.7")
    testFixturesImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
    testFixturesImplementation("org.liquibase:liquibase-core:4.32.0")
    testFixturesImplementation("com.zaxxer:HikariCP:7.1.0")

    testImplementation(testFixtures(project(":lark-actor")))
    testImplementation("org.liquibase:liquibase-core:4.32.0")
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
