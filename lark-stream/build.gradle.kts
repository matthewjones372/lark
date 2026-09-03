val pekkoVersion = "1.2.1"
val scalaBinary = "2.13"

// The whole library: the Kotlin standard library, pekko-stream and arrow-core.
// NoOtherDependenciesTest asserts exactly that, on the classpath a consumer
// actually gets. Arrow is `api` here and unused so far — the split operators
// the next spec entry adds are written in `Either`, and a module that grew a
// dependency between two releases is a worse surprise than one that declared
// it up front.
dependencies {
    api(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    api("org.apache.pekko:pekko-stream_$scalaBinary")
    api("io.arrow-kt:arrow-core:2.1.2")

    // The actor system a contract test runs on, owned by a JUnit 5 extension.
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_$scalaBinary")
    // TestSink, for the one test that runs what `toSource()` hands back
    // through plain Pekko rather than through this library.
    testImplementation("org.apache.pekko:pekko-stream-testkit_$scalaBinary")
    // The compiler, so that "this does not compile" can be a test rather than
    // a sentence in a document that goes stale.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
}

// Read at configuration time: a provider that reached for the project at
// execution time would not survive the configuration cache.
val repoRoot = rootProject.projectDir.absolutePath
val judgedSources = files(layout.projectDirectory.dir("src/main/kotlin"))
val readme = files(rootProject.layout.projectDirectory.file("README.md"))

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    // Which sources FunctionalStyleTest judges is decided here, and declared
    // as an input of the task that runs it: a test resolving them itself would
    // read files Gradle knows nothing about, and a violation would ride green
    // builds until `--rerun-tasks`.
    inputs.files(judgedSources).withPropertyName("judgedSources")
    // ReadmeExampleTest compiles the example out of the README, so an edit to
    // it is a change to what this task tests: without this the example would
    // ride a cached green until something else in the module moved.
    inputs.files(readme).withPropertyName("readme")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Ddipper.core.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Ddipper.style.repoRoot=$repoRoot",
                "-Ddipper.style.sources=" +
                    judgedSources.joinToString(File.pathSeparator) { it.absolutePath },
            )
        },
    )
}
