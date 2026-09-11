plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("larkWiring") {
            id = "io.github.matthewjones372.lark.wiring"
            implementationClass = "io.github.matthewjones372.lark.app.gradle.LarkWiringPlugin"
            displayName = "lark-app wiring"
            description = "Checks a project's lark-app graphs on every build, and renders each one."
        }
    }
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
