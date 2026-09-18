// The facade alone. A service brings its own backend — logback, log4j, or the
// one its platform already installed — and this module decides what a LogLine
// looks like when it gets there, not where it ends up.
//
// Declared at the oldest version this compiles against, and compiled against it
// too, so the compile classpath is the contract: a 2.x-only method cannot be
// reached for by accident. Resolution takes the highest of what is asked for, so
// this can only be raised by a consumer and never lowers anyone — where a 2.x
// floor would move a service on 1.7 across the change from StaticLoggerBinder to
// ServiceLoader providers, and its logging would go quiet for adding a module.
val slf4jVersion = "1.7.36"

dependencies {
    api(project(":lark"))
    api("org.slf4j:slf4j-api:$slf4jVersion")

    // Run the tests against a current one, since that is what resolution gives
    // most services: compiled against the floor, asserted against the ceiling.
    testImplementation("org.slf4j:slf4j-api:2.0.17")

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
