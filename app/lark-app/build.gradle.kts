dependencies {
    api(project(":lark"))
    testImplementation(project(":lark-assert"))
}

val repoRoot = rootProject.projectDir.absolutePath

@Suppress("UNCHECKED_CAST")
val publishedModules = rootProject.extra["publishedModules"] as List<String>

@Suppress("UNCHECKED_CAST")
val describedModules = rootProject.extra["describedModules"] as List<String>

// The pages AgentDocsTest holds to the published modules.
val agentDocs = files(
    rootProject.layout.projectDirectory.file("llms.txt"),
    rootProject.layout.projectDirectory.file("plugins/lark/skills/lark/SKILL.md"),
)

// The pages DocsTest reads for import order and coordinates.
val guides = files(rootProject.layout.projectDirectory.file("README.md")) +
    fileTree(rootProject.layout.projectDirectory.dir("docs")) { include("*.md") }

// The page WiringDiagramTest holds to what render answers, declared so editing
// it re-runs the test rather than leaving a stale answer behind.
val documentedDiagram = files(
    rootProject.layout.projectDirectory.file("docs/cookbook.md"),
    rootProject.layout.projectDirectory.file("docs/wiring.png.sha256"),
)

// CheckTest runs discovery against this module's own test output, which is the only way to test a
// scan that reads class files rather than a list someone passed it.
val testClassDirs = sourceSets.test.get().output.classesDirs

tasks.test {
    inputs.files(documentedDiagram).withPropertyName("documentedDiagram")
    inputs.files(agentDocs).withPropertyName("agentDocs")
    inputs.files(guides).withPropertyName("guides")
    inputs.property("publishedModules", publishedModules)
    inputs.property("describedModules", describedModules)
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.app.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dlark.app.repoRoot=$repoRoot",
                "-Dlark.app.publishedModules=${publishedModules.joinToString(",")}",
                "-Dlark.app.describedModules=${describedModules.joinToString(",")}",
                "-Dlark.app.testClasses=" +
                    testClassDirs.files.joinToString(File.pathSeparator) { it.absolutePath },
            )
        },
    )
}
