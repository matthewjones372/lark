// A stream that names its failure: Pekko Streams, lark, and the Arrow that
// arrives with it. NoOtherDependenciesTest asserts exactly that, on the
// classpath a consumer actually gets.

// Pekko publishes one artifact per Scala binary version, so the two spellings
// are a single coordinate and live beside each other rather than in
// gradle.properties, which no other module reads.
val pekkoVersion = "1.2.1"
val scalaBinary = "2.13"

dependencies {
    api(project(":lark"))
    // `awaitExit` waits through lark-pekko's `await`, so the cancellation bridge a handler gets
    // and the one a run gets are the same code. It adds pekko-actor, which pekko-stream brings
    // anyway.
    api(project(":lark-pekko"))
    api(platform("org.apache.pekko:pekko-bom_$scalaBinary:$pekkoVersion"))
    api("org.apache.pekko:pekko-stream_$scalaBinary")

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
val documentedExample = files(
    rootProject.layout.projectDirectory.file("docs/stream.md"),
    rootProject.layout.projectDirectory.file("README.md"),
)

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
    // ReadmeExampleTest compiles the example out of docs/stream.md and holds the
    // README's copy of it to the same text, so an edit to either page is a change
    // to what this task tests: without this the example would ride a cached green
    // until something else in the module moved.
    inputs.files(documentedExample).withPropertyName("documentedExample")
    // DoesNotCompileTest runs the Kotlin compiler inside the test JVM. Gradle's
    // default heap turns that into a garbage-collection stall long enough to
    // trip the suite's 60s timeout.
    maxHeapSize = "2g"
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.stream.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dlark.stream.repoRoot=$repoRoot",
                "-Dlark.stream.sources=" +
                    judgedSources.joinToString(File.pathSeparator) { it.absolutePath },
            )
        },
    )
}
