// Kafka as a lark Stream: lark-stream and Pekko's own Kafka connector, and
// nothing else. NoOtherDependenciesTest asserts that on the classpath a
// consumer actually gets.

val pekkoVersion = "1.2.1"
val scalaBinary = "2.13"

// 1.1.0 is built against pekko-stream 1.1, which Pekko keeps binary compatible
// through 1.x. 2.0.0-M1 needs Pekko 2.
val connectorVersion = "1.1.0"

dependencies {
    api(project(":lark-stream"))
    api("org.apache.pekko:pekko-connectors-kafka_$scalaBinary:$connectorVersion")

    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_$scalaBinary:$pekkoVersion")
    // A broker in the test JVM, so the suite needs no Docker. Its kafka-clients
    // matches the connector's.
    testImplementation("io.github.embeddedkafka:embedded-kafka_$scalaBinary:3.8.0")
    // The compiler, so that which overload a call resolves to, and what does not compile, are tests.
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    // CommittedCompileTest runs the compiler in the test JVM, which Gradle's default heap stalls.
    maxHeapSize = "2g"
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.kafka.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
