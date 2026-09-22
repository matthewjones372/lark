// lark over the JDK's own structured concurrency. StructuredTaskScope is a preview API in 27
// (JEP 533) and proposed final in 28 unchanged (JEP 543), so this module compiles against 27 and is
// built and tested, but not published (see `unpublished` in the root build).
dependencies {
    api(project(":lark"))
}

kotlin {
    jvmToolchain(27)
}

// Kotlin 2.4 emits bytecode for 26 at most and reads 27's class library regardless. The Java task
// has no sources and only has to agree with it for Gradle's target check.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(26)
}

tasks.test {
    // Kotlin marks no class file as preview and the JDK checks none of these calls at runtime, so the
    // tests run without --enable-preview: what a consumer on 27 would get.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.structured.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
