// One suite of described pipelines, run on every backend: a test-only module, so that no backend has to
// depend on another to be held to the same answers. A backend joins by being added to ParityTest's list.

dependencies {
    testImplementation(project(":lark-stream-pekko"))
    testImplementation(project(":lark-stream-forks"))
    testImplementation(project(":lark-stream-test"))
    testImplementation(project(":lark-stream-actors"))
}

// The suite asks each backend which operators it runs, which is the node tree the opt-in covers.
kotlin {
    compilerOptions {
        optIn.add("io.github.matthewjones372.lark.stream.StreamSpi")
    }
}

// HardenedTest runs the whole suite this many times on Forks; -Psoak=N for more.
tasks.test {
    val soak = providers.gradleProperty("soak").orElse("200")
    inputs.property("soak", soak)
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dlark.stream.soak=${soak.get()}") })
}
