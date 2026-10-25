// Pekko 1.2.x for the Scala 2.13 build, as the rest of this author's Pekko
// modules use. `virtual-thread-executor` arrived in Pekko 1.1, and a
// dispatcher without it is one surefold refuses.
val pekkoVersion = "1.2.1"
val scalaBinary = "2.13"

// pekko-actor alone: ClassicActorSystemProvider lives there and both the
// classic and the typed system implement it, so the typed artifact is not
// needed to take either.
dependencies {
    api(project(":surefold"))
    api("org.apache.pekko:pekko-actor_$scalaBinary:$pekkoVersion")

    testImplementation(project(":surefold-assert"))
}

tasks.test {
    // The main runtime classpath, so the dependency test can assert on what is
    // actually shipped rather than on what the test JVM happens to load.
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.pekko.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
