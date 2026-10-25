// liquibase-core and the JDK's javax.sql. No driver: the DataSource is the
// application's, and so is what it connects to.
val liquibaseVersion = "4.32.0"

dependencies {
    api(project(":surefold-app"))
    api("org.liquibase:liquibase-core:$liquibaseVersion")

    // A real Postgres, in a container.
    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.liquibase.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
