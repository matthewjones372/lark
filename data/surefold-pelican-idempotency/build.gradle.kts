// surefold-idempotency behind Pelican's IdempotencyStore (spec 0230): Pelican's keyed endpoints keep their keys in
// surefold_idempotency on Postgres, shared by every instance. NoOtherDependenciesTest asserts the runtime classpath.

dependencies {
    api(project(":surefold-idempotency"))
    api("io.github.matthewjones372:pelican-core:1.2.0")

    testImplementation(testFixtures(project(":surefold-actor-journal-jdbc")))
    testImplementation("org.liquibase:liquibase-core:4.32.0")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.pelican.idempotency.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
