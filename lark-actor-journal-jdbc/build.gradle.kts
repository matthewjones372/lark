// lark-actor's Journal on JDBC (spec 0072): the JDK's java.sql and nothing else. No driver and no pool: the
// DataSource is the service's. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":lark-actor"))

    testImplementation(testFixtures(project(":lark-actor")))
    testImplementation("com.h2database:h2:2.3.232")
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
