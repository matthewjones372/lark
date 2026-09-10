// liquibase-core and the JDK's javax.sql. No driver: the DataSource is the
// application's, and so is what it connects to.
val liquibaseVersion = "4.32.0"

dependencies {
    api(project(":lark-app"))
    api("org.liquibase:liquibase-core:$liquibaseVersion")

    testImplementation("com.h2database:h2:2.3.232")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.app.liquibase.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
