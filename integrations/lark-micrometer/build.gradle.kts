// The registry interface and nothing under it. A service brings the registry it
// already has — Prometheus, Datadog, StatsD, OTLP — and this module decides what
// a measurement looks like when it arrives, not where it goes.
//
// Declared at the oldest version this compiles against, for the reason lark-slf4j
// declares slf4j at 1.7.36: resolution takes the highest asked for, so this can
// only be raised by a consumer and never lowers anyone.
val micrometerVersion = "1.12.0"

dependencies {
    api(project(":lark"))
    api("io.micrometer:micrometer-core:$micrometerVersion")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.micrometer.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
