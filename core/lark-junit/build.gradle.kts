// Lark's tests on the JUnit Platform (spec 0174): a TestEngine that runs Suite objects, so Gradle, the IDE and
// CI read the results as they read Jupiter's. It depends on lark, lark-assert for how a failed expectation is
// reported (spec 0175), and junit-platform-engine, and nothing else, which NoOtherDependenciesTest asserts.
dependencies {
    api(project(":lark"))
    implementation(project(":lark-assert"))
    implementation("org.junit.platform:junit-platform-engine:6.1.3")

    testImplementation("org.junit.platform:junit-platform-testkit")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.junit.platform:junit-platform-reporting")
    // ExpectationsCompileTest compiles suites that must not compile.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
}

tasks.test {
    // Suites that fail on purpose, run only by the tests that select them, never by this task.
    exclude("**/junit/samples/**")

    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.junit.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}

kotlin {
    compilerOptions {
        // Expectations' file is @MustUseReturnValues; the checker reports a dropped one only where this is set.
        freeCompilerArgs.add("-Xreturn-value-checker=check")
    }
}
