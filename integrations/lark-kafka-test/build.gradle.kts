// A Kafka broker in the test JVM for a service's own tests (spec 0161): lark-kafka, embedded-kafka, and nothing else.
// NoOtherDependenciesTest asserts that on the classpath a consumer actually gets.

val scalaBinary = "2.13"
val embeddedKafka = "io.github.embeddedkafka:embedded-kafka_$scalaBinary:4.3.1"

// What the module may bring, resolved from those two alone: anything else on its classpath was added beside them.
val allowed: Configuration by configurations.creating {
    isCanBeConsumed = false
}

dependencies {
    // The Topic its helpers take, and through lark-stream, the Resource a suite shares it as.
    api(project(":lark-kafka"))
    // The broker itself. Its kafka-clients matches lark-kafka's.
    api(embeddedKafka)
    allowed(project(":lark-kafka"))
    allowed(embeddedKafka)

    testImplementation(project(":lark-assert"))
    testImplementation(project(":lark-junit"))
    // The broker's own log, errors only: a fatal fault is logged here before KafkaBroker turns its exit into a throw.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "error")
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    inputs.files(allowed).withPropertyName("allowedClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dlark.kafka.test.runtimeClasspath=" +
                    mainRuntime.get().joinToString(File.pathSeparator) { it.name },
                "-Dlark.kafka.test.allowedClasspath=" + allowed.joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
