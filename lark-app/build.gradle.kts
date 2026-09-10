dependencies {
    api(project(":lark"))
}

val repoRoot = rootProject.projectDir.absolutePath

// The page WiringDiagramTest holds to what render answers, declared so editing
// it re-runs the test rather than leaving a stale answer behind.
val documentedDiagram = files(rootProject.layout.projectDirectory.file("docs/cookbook.md"))

tasks.test {
    inputs.files(documentedDiagram).withPropertyName("documentedDiagram")
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.app.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dlark.app.repoRoot=$repoRoot",
            )
        },
    )
}
