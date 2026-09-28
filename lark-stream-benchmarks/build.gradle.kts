/**
 * lark-stream measured by JMH: the baseline 0046 and 0047 report against.
 *
 * The JMH wiring is pelican's `benchmarks` module taken as it is: the bytecode generator reads the
 * compiled Kotlin, because `kapt` would be a compiler plugin for one module. Nothing here is
 * published, and nothing in `build` runs it.
 */

val jmhVersion = "1.37"

dependencies {
    implementation(project(":lark-stream-pekko"))
    implementation(project(":lark-stream-forks"))
    implementation(project(":lark-stream-actors"))
    implementation("org.openjdk.jmh:jmh-core:$jmhVersion")
}

val jmhGenerator = configurations.register("jmhGenerator")

dependencies { jmhGenerator("org.openjdk.jmh:jmh-generator-bytecode:$jmhVersion") }

val generatedStubSources = layout.buildDirectory.dir("generated/jmh/java")
val generatedStubResources = layout.buildDirectory.dir("generated/jmh/resources")

val benchmarkClasses = tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin")
    .flatMap { it.destinationDirectory }

val benchmarkRuntimeClasspath = the<SourceSetContainer>()["main"].runtimeClasspath

// Named rather than inherited: a task registered by hand does not pick up the Kotlin toolchain, and
// a number measured on whichever JDK ran the daemon is a number nobody can reproduce.
val toolchains = extensions.getByType<JavaToolchainService>()
val benchmarkJdk = JavaLanguageVersion.of(25)
val toolchainLauncher = toolchains.launcherFor { languageVersion.set(benchmarkJdk) }

val generateBenchmarkStubs = tasks.register<JavaExec>("generateBenchmarkStubs") {
    description = "Generates JMH's benchmark stubs from the compiled Kotlin"
    mainClass.set("org.openjdk.jmh.generators.bytecode.JmhBytecodeGenerator")
    classpath(jmhGenerator, benchmarkRuntimeClasspath)
    javaLauncher.set(toolchainLauncher)
    inputs.dir(benchmarkClasses).withPropertyName("benchmarkClasses")
    outputs.dir(generatedStubSources)
    outputs.dir(generatedStubResources)
    argumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                benchmarkClasses.get().asFile.path,
                generatedStubSources.get().asFile.path,
                generatedStubResources.get().asFile.path,
                "reflection",
            )
        },
    )
    // The generator appends to `BenchmarkList`, so a renamed benchmark would leave its old name behind.
    doFirst {
        delete(generatedStubSources)
        delete(generatedStubResources)
    }
}

val compileBenchmarkStubs = tasks.register<JavaCompile>("compileBenchmarkStubs") {
    description = "Compiles the generated JMH stubs"
    dependsOn(generateBenchmarkStubs)
    source(generatedStubSources)
    classpath = benchmarkRuntimeClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/jmh/java"))
    javaCompiler.set(toolchains.compilerFor { languageVersion.set(benchmarkJdk) })
    options.encoding = "UTF-8"
}

/** `./gradlew :lark-stream-benchmarks:jmh`, with `-PbenchmarkArgs="..."` passed through to JMH. */
tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the lark-stream JMH benchmarks; nothing else depends on it"
    dependsOn(compileBenchmarkStubs)
    mainClass.set("org.openjdk.jmh.Main")
    classpath(
        compileBenchmarkStubs.map { it.destinationDirectory },
        generatedStubResources,
        benchmarkRuntimeClasspath,
    )
    // A forked JMH child inherits `java.home`, so the launcher decides which JDK is measured.
    javaLauncher.set(toolchainLauncher)
    val results = layout.buildDirectory.file("jmh-result.json").get().asFile
    args("-prof", "gc", "-rf", "json", "-rff", results.path)
    args(providers.gradleProperty("benchmarkArgs").getOrElse("").split(" ").filter { it.isNotBlank() })
}

/**
 * `-Pbaseline=a.json -Pcandidate=b.json`: red when a row of the candidate is slower than the baseline's.
 *
 * Slower means both more than [tolerance] slower and outside both error bars. A shared runner is noisy, and
 * a gate that fails on noise is one people learn to rerun. Every row is written to `jmh-compare.txt`, so
 * drift is visible before it trips. `gate.sh` produces the two files on one machine, back to back.
 */
tasks.register("jmhCompare") {
    group = "verification"
    description = "Fails when a benchmark row got slower than the baseline's"
    val baseline = providers.gradleProperty("baseline")
    val candidate = providers.gradleProperty("candidate")
    val tolerance = providers.gradleProperty("tolerance").map(String::toDouble).orElse(defaultTolerance)
    val report = layout.buildDirectory.file("jmh-compare.txt")
    // Paths are read from the repository root, where gate.sh and a person typing them both stand.
    val root = rootProject.projectDir
    doLast {
        val before = scores(root.resolve(baseline.get()))
        val after = scores(root.resolve(candidate.get()))
        val rows = after.keys.sorted().map { name ->
            compared(name, before[name], after.getValue(name), tolerance.get())
        }
        val table = rows.joinToString("\n") { it.line }
        report.get().asFile.writeText(table + "\n")
        logger.lifecycle(table)
        val slower = rows.filter { it.slower }
        if (slower.isNotEmpty()) {
            val lines = slower.joinToString("\n") { it.line }
            throw GradleException("${slower.size} benchmark row(s) got slower:\n$lines")
        }
    }
}

val defaultTolerance = 0.10

/** A benchmark's score and error, by name, from a JMH JSON result. */
fun scores(result: File): Map<String, Pair<Double, Double>> {
    @Suppress("UNCHECKED_CAST")
    val rows = groovy.json.JsonSlurper().parse(result) as List<Map<String, Any?>>
    return rows.associate { row ->
        @Suppress("UNCHECKED_CAST")
        val metric = row.getValue("primaryMetric") as Map<String, Any?>
        val score = (metric.getValue("score") as Number).toDouble()
        val error = (metric["scoreError"] as? Number)?.toDouble()?.takeUnless { it.isNaN() } ?: 0.0
        (row.getValue("benchmark") as String).substringAfterLast("benchmarks.") to (score to error)
    }
}

class Compared(val line: String, val slower: Boolean)

fun compared(name: String, before: Pair<Double, Double>?, after: Pair<Double, Double>, tolerance: Double): Compared {
    val (score, error) = after
    if (before == null) {
        return Compared("%-45s %12s -> %10.1f ± %.1f  new".format(name, "", score, error), false)
    }
    val (was, wasError) = before
    val change = score / was - 1
    val slower = change > tolerance && score - error > was + wasError
    val verdict = if (slower) "SLOWER" else "ok"
    return Compared(
        "%-45s %10.1f ± %.1f -> %10.1f ± %.1f  %+6.1f%%  %s"
            .format(name, was, wasError, score, error, change * 100, verdict),
        slower,
    )
}

/** Each benchmark's pipeline, measured once and drawn with where its time went, into `build/profiles`. */
tasks.register<JavaExec>("profiles") {
    group = "verification"
    description = "Draws each benchmark's pipeline with its profile, as Mermaid and as text"
    mainClass.set("io.github.matthewjones372.lark.stream.benchmarks.ProfilesKt")
    classpath(benchmarkRuntimeClasspath)
    javaLauncher.set(toolchainLauncher)
    val out = layout.buildDirectory.dir("profiles")
    outputs.dir(out)
    argumentProviders.add(CommandLineArgumentProvider { listOf(out.get().asFile.path) })
}
