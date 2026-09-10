val pekkoVersion = "1.2.1"
val scalaBinary = "2.13"

dependencies {
    api(project(":lark-app"))
    api(project(":lark-pekko"))
    api("org.apache.pekko:pekko-actor-typed_$scalaBinary:$pekkoVersion")

    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_$scalaBinary:$pekkoVersion")
    // The Kotlin compiler in the test JVM, so the cookbook's recipes are compiled
    // rather than believed.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
}

val repoRoot = rootProject.projectDir.absolutePath

// The page CookbookTest compiles, declared so that editing it re-runs the test
// rather than leaving a stale answer behind.
val documentedRecipes = files(rootProject.layout.projectDirectory.file("docs/cookbook.md"))

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    inputs.files(documentedRecipes).withPropertyName("documentedRecipes")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.app.pekko.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dlark.app.pekko.repoRoot=$repoRoot",
            )
        },
    )
}
