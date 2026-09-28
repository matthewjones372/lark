// lark-actor as a cluster (spec 0069): discovery, gossip membership and downing, on lark-actor-remote.
// NoOtherDependenciesTest asserts the runtime classpath is lark-actor-remote and what it brings, and nothing else.

dependencies {
    api(project(":lark-actor-remote"))

    // A sharded persistent entity keeps its state across a move only on a journal every node reaches (spec 0072).
    testImplementation(project(":lark-actor-journal-jdbc"))
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation(project(":lark-app-liquibase"))
    // The TLS test certificates (spec 0073).
    testImplementation(testFixtures(project(":lark-actor-remote")))
    // GuideExampleTest compiles the examples out of docs/cluster.md (spec 0084).
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    // The guide's read model follows the journal on a stream (spec 0084).
    testImplementation(project(":lark-actor-projection"))
    testImplementation(project(":lark-stream-forks"))
    // The guide's stopping section wires a cluster into an application, and probes it.
    testImplementation(project(":lark-app-actor"))
}

// Read at configuration time, so the configuration cache survives it.
val repoRoot = rootProject.projectDir.absolutePath
val guide = files(
    rootProject.layout.projectDirectory.file("docs/cluster.md"),
    rootProject.layout.projectDirectory.file("README.md"),
)

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    // An edit to the guide is a change to what this task tests, or its examples would ride a cached green.
    inputs.files(guide).withPropertyName("guide")
    // The embedded compiler needs more than Gradle's default heap, or a GC stall trips the 60s timeout.
    maxHeapSize = "2g"
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.cluster.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dlark.cluster.repoRoot=$repoRoot",
            )
        },
    )
}
