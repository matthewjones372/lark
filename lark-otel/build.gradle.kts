// The API alone. A service brings its own SDK, exporter and agent; this module
// decides where a Context lives, not what is done with one.
val otelVersion = "1.51.0"

dependencies {
    api(project(":lark"))
    api("io.opentelemetry:opentelemetry-api:$otelVersion")

    // The SDK, and not opentelemetry-sdk-testing: that artifact registers a
    // ContextStorageProvider of its own, which would win the ServiceLoader race
    // and leave this module's storage untested. The exporter it would have
    // supplied is a dozen lines below.
    testImplementation("io.opentelemetry:opentelemetry-sdk:$otelVersion")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.otel.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
