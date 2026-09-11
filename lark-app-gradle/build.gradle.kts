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
    testImplementation(gradleTestKit())
    larkAppUnderTest(project(":lark-app"))
}

tasks.test {
    inputs.files(larkAppUnderTest).withPropertyName("larkAppUnderTest")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.app.underTest=" +
                    larkAppUnderTest.joinToString(File.pathSeparator) { it.absolutePath },
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
