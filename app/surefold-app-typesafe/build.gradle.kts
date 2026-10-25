// Typesafe Config itself, not a view of it. Everything a HOCON file can say
// stays sayable: substitution, merging, lists, objects, durations, memory sizes.
val typesafeVersion = "1.4.3"

dependencies {
    api(project(":surefold-app"))
    api("com.typesafe:config:$typesafeVersion")

    // The checker's copy of Secrets.default's names, for SecretNamesSuite. The checker's own classes reach
    // the compiler's, which a test outside the compiler has to bring.
    testImplementation(project(":surefold-app-compiler"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:${rootProject.extra["surefoldKotlinVersion"]}")
    testImplementation(project(":surefold-assert"))
}

// The page ReportedSuite holds to what the report answers, declared so editing it re-runs the test.
val documentedReport = files(rootProject.layout.projectDirectory.file("docs/cookbook.md"))

tasks.test {
    // What LoadedLayersSuite forces on top once -Dconfig.override_with_env_vars asks it to.
    environment("CONFIG_FORCE_surefold_layered_forced", "from-env")
    inputs.files(documentedReport).withPropertyName("documentedReport")
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.app.typesafe.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dsurefold.app.typesafe.repoRoot=${rootProject.projectDir.absolutePath}",
            )
        },
    )
}
