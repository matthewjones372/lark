// A booking service from the data modules (spec 0229): sessions, tenancy, jobs, idempotency and files together, as
// the layer a Pelican handler would call. An example, never published. NoOtherDependenciesTest asserts the runtime
// classpath is exactly what is declared here.

dependencies {
    implementation(project(":surefold-auth"))
    implementation(project(":surefold-tenancy"))
    implementation(project(":surefold-jobs"))
    // Each confirmation runs as the tenant its job names.
    implementation(project(":surefold-jobs-tenancy"))
    implementation(project(":surefold-idempotency"))
    implementation(project(":surefold-files"))

    // Postgres with roles row-level security binds and one it does not.
    testImplementation(testFixtures(project(":surefold-tenancy")))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.bookings.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
