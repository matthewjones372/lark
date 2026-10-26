// An actor written in Kotlin on Pekko's typed actors (spec 0058): asking one, and writing one, without the wiring
// graph. surefold-app-pekko builds on it; a service that only writes actors takes this alone.
val pekkoVersion = "1.2.1"
val scalaBinary = "2.13"

dependencies {
    api(project(":surefold-pekko"))
    api("org.apache.pekko:pekko-actor-typed_$scalaBinary:$pekkoVersion")

    testImplementation(project(":surefold-assert"))
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is actually shipped.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.actor.pekko.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
