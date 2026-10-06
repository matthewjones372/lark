// Kafka as a lark Stream on any backend: lark-stream and the Kafka client, and nothing else.
// NoOtherDependenciesTest asserts that on the classpath a consumer actually gets.

val pekkoVersion = "1.2.1"
val scalaBinary = "2.13"

dependencies {
    api(project(":lark-stream"))
    // Kafka 4.3, which Confluent 8.3's serializers are built on. lark-kafka-pekko's connector is built against
    // 3.8 and resolves up to this one, which its tests run on; the connector built on 4 is a 2.0 milestone.
    api("org.apache.kafka:kafka-clients:4.3.0")

    // Both backends a consumer loop is run on in the tests, against one broker.
    testImplementation(project(":lark-stream-forks"))
    testImplementation(project(":lark-stream-pekko"))
    testImplementation("org.apache.pekko:pekko-actor-testkit-typed_$scalaBinary:$pekkoVersion")
    // A broker in the test JVM, so the suite needs no Docker. Its kafka-clients matches the one above.
    testImplementation("io.github.embeddedkafka:embedded-kafka_$scalaBinary:4.3.1")
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.kafka.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
