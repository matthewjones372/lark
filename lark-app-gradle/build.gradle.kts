plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("larkWiring") {
            id = "io.github.matthewjones372.lark.wiring"
            implementationClass = "io.github.matthewjones372.lark.app.gradle.LarkWiringPlugin"
            displayName = "lark-app wiring"
            description = "Checks a project's lark-app graphs as it compiles, and renders each one."
        }
    }
}

// `java-gradle-plugin` already publishes this module as `pluginMaven`, with the marker beside it.
// Configuring it as a plain Kotlin library would add a second publication at the same coordinates —
// harmless installing locally, a duplicate artifact the Central Portal rejects.
mavenPublishing {
    configure(
        com.vanniktech.maven.publish.GradlePlugin(
            javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = true,
        ),
    )
}

// The plugin is tested by running a build, so the test project needs the very lark-app this repo
// builds — not one resolved from a repository, which would test the last release instead.
val larkAppUnderTest: Configuration by configurations.creating

dependencies {
    // For `KotlinCompilerPluginSupportPlugin`. The API artifact rather than the plugin itself: this
    // jar is applied alongside whatever Kotlin plugin the consumer chose, not in front of it.
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin-api:2.4.10")

    testImplementation(gradleTestKit())
    larkAppUnderTest(project(":lark-app"))
}

// The checker is resolved from a repository at compile time, so the plugin has to name a version.
// Generated rather than written down, so cutting a release does not leave a plugin pointing at the
// release before it.
val checkerCoordinates = tasks.register<WriteProperties>("checkerCoordinates") {
    destinationFile.set(layout.buildDirectory.file("generated/lark/checker.properties"))
    property("group", project.group.toString())
    property("artifact", "lark-app-compiler")
    property("version", project.version.toString())
}

sourceSets.main {
    resources.srcDir(checkerCoordinates.map { it.destinationFile.get().asFile.parentFile })
}

// The compiler plugin is resolved from a repository by coordinates, not handed over as a file, so a
// TestKit build needs one to resolve it from. The root project already declares `local`.
val checkerRepo: Provider<Directory> = rootProject.layout.buildDirectory.dir("repo")

tasks.test {
    inputs.files(larkAppUnderTest).withPropertyName("larkAppUnderTest")
    // Both halves, resolved by a TestKit build the way a consumer's build resolves them. Injecting
    // this jar with `withPluginClasspath` instead would put it in a classloader of its own, where
    // the Kotlin plugin cannot see that it implements `KotlinCompilerPluginSupportPlugin`.
    dependsOn(
        ":lark-app-compiler:publishAllPublicationsToLocalRepository",
        "publishAllPublicationsToLocalRepository",
    )
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.app.underTest=" +
                    larkAppUnderTest.joinToString(File.pathSeparator) { it.absolutePath },
                "-Dlark.checker.repo=" + checkerRepo.get().asFile.absolutePath,
                "-Dlark.checker.version=" + version,
            )
        },
    )
}

tasks.test {
    // A TestKit test starts a Gradle build of its own: on a cold machine that is a distribution to
    // download and a dependency graph to resolve, and the 60s this build gives every other test is
    // the wrong order of magnitude. It passed here and timed out on the first CI run there was.
    systemProperty("junit.jupiter.execution.timeout.default", "10m")
}
