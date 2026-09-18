// The facade alone. A service brings its own backend — logback, log4j, or the
// one its platform already installed — and this module decides what a LogLine
// looks like when it gets there, not where it ends up.
val slf4jVersion = "2.0.17"

dependencies {
    api(project(":lark"))
    api("org.slf4j:slf4j-api:$slf4jVersion")

    // A claim about a backend asserted against a fake backend is a claim about
    // the fake, so the tests run against the one most services use.
    testImplementation("ch.qos.logback:logback-classic:1.5.20")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.slf4j.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
