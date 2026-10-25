// surefold-app-openfeature on flagd: flags written in flagd's schema as a section of the service's own HOCON,
// evaluated in-process. flagd brings gRPC and netty with it, which is why this is a module of its own.
dependencies {
    api(project(":surefold-app-openfeature"))
    api(project(":surefold-app-typesafe"))
    api("dev.openfeature.contrib.providers:flagd:0.15.0")

    testImplementation(project(":surefold-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.flagd.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
