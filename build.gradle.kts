plugins {
    kotlin("jvm") version "2.4.10" apply false
    id("com.diffplug.spotless") version "8.10.0"
    id("dev.detekt") version "2.0.0-alpha.6" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
    // The version comes from the nearest `v` tag rather than a property, so
    // cutting a release is `git tag v0.1.0 && git push --tags` and nothing
    // else. An untagged commit is a -SNAPSHOT of the next one.
    id("pl.allegro.tech.build.axion-release") version "1.21.3"
    // So the root project has `check`/`build`, and the scripts formatted here
    // are covered by a plain `./gradlew build` like everything else.
    base
    // Publishing to the Central Portal, which is the only way in since OSSRH
    // closed. It wraps `maven-publish` and `signing`, so neither is applied
    // here directly.
    id("com.vanniktech.maven.publish") version "0.37.0" apply false
    // Renders the KDoc into the javadoc jar the published module ships.
    id("org.jetbrains.dokka") version "2.1.0" apply false
    // What the published module's binary surface is, as a file somebody reads
    // in a diff. The golden-file argument, applied to the Kotlin API instead of
    // to the HTTP one.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.18.1"
}

scmVersion {
    tag { prefix.set("v") }
    // Plain `0.1.0-SNAPSHOT` off a tag rather than axion's default, which
    // decorates it with the branch name. The README tells a contributor to
    // install locally and depend on the version; that version should not
    // change with the branch they happen to be on.
    versionCreator("simple")
}

// Read once, at configuration time: `scmVersion.version` shells out to git.
val scmVer: String = scmVersion.version

/**
 * ktlint rather than ktfmt: ktfmt reflows, and this codebase is hand-laid-out
 * on purpose — banner comments, aligned trailing comments, KDoc written as
 * prose. The rules that make ktlint disagree with any of that are turned off
 * in `.editorconfig`, which is also where the line length lives.
 */
val ktlintVersion = "1.8.0"

// Spotless does not pick these up from .editorconfig for every source set, so
// they are handed to the ktlint step directly. The reasoning for each lives in
// .editorconfig beside the rest, which is also what the IDE reads.
val ktlintOverrides = mapOf("ktlint_standard_kdoc" to "disabled")

// The root project builds nothing, but Spotless resolves ktlint here.
repositories { mavenCentral() }

spotless {
    kotlinGradle {
        // settings.gradle.kts and this file. The module formats its own build
        // script in the `subprojects` block below.
        target("*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
}

/** One line per module, so a Maven search result says what the artifact is. */
val moduleDescriptions = mapOf(
    "lark" to "Arrow's fx on virtual threads: parZip, parMap, raceN, resources and schedules, minus the suspend.",
    "lark-pekko" to "lark on Pekko: a dispatcher as the executor, and Pekko's stages awaited from a virtual thread.",
    "lark-stream" to "A stream that names its failure: Stream<E, A> over Pekko Streams, formerly dipper.",
)

// The floor is a ratchet against regression, not a target to code towards — a
// test written to move a percentage is worth less than no test at all.
kover {
    reports {
        total {
            verify {
                rule {
                    minBound(90)
                }
            }
        }
    }
}

dependencies {
    subprojects.forEach { kover(project(it.path)) }
}

// A floor nobody runs is not a floor: `./gradlew build` checks it.
tasks.named("check") {
    dependsOn("koverVerify")
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    repositories { mavenCentral() }

    // The namespace verified on the Central Portal, against the GitHub
    // account it names.
    group = "io.github.matthewjones372"
    version = scmVer

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)

        compilerOptions {
            // A warning nobody reads is a defect nobody fixed; the build says so.
            allWarningsAsErrors.set(true)
            // Without this, every interface with a method body also gets a
            // `DefaultImpls` class holding a copy of it, and both are published
            // surface the BCV gate then has to keep. The interfaces here
            // already emit real JVM default methods, so the copies are the ABI
            // of a compatibility mode with nothing behind it: Kotlin callers
            // never reach them, and the Java caller they exist for would have
            // had to write `JsonValue.DefaultImpls.x(this)` by hand.
            jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
        }
    }

    dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("org.junit.jupiter:junit-jupiter:6.1.3")
        // Assertions only. The tests still run on the JUnit platform — kotest
        // is here for its matchers and for the failure messages they produce,
        // not as a second test framework.
        "testImplementation"("io.kotest:kotest-assertions-core:6.2.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()

        systemProperty("junit.jupiter.execution.timeout.default", "60s")
    }

    apply(plugin = "org.jetbrains.kotlinx.kover")

    apply(plugin = "dev.detekt")
    extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
        // The deviations live in one file for the whole build; see its header.
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }
    // The plain `detekt` task cannot see types, so the rules that need them —
    // ForbiddenMethodCall, for one — are silently skipped there. `check`
    // depends on the type-resolving pair instead.
    tasks.named("check") { dependsOn("detektMain", "detektTest") }

    apply(plugin = "com.diffplug.spotless")
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
        }
    }

    apply(plugin = "com.vanniktech.maven.publish")
    apply(plugin = "org.jetbrains.dokka")

    extensions.configure<com.vanniktech.maven.publish.MavenPublishBaseExtension> {
        // Sources are not an optional extra for a library someone else has to
        // debug, and Maven Central will not accept a release without a javadoc
        // jar. Dokka fills it: an empty jar leaves javadoc.io blank, which puts
        // the KDoc out of reach of anyone who has not cloned the repository.
        configure(
            com.vanniktech.maven.publish.KotlinJvm(
                javadocJar = com.vanniktech.maven.publish.JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                sourcesJar = true,
            ),
        )

        pom {
            name.set(this@subprojects.name)
            description.set(moduleDescriptions[this@subprojects.name] ?: "Part of Lark.")
            url.set("https://github.com/matthewjones372/lark")
            licenses {
                license {
                    name.set("The Apache License, Version 2.0")
                    url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                }
            }
            developers {
                developer {
                    id.set("matthewjones372")
                    name.set("Matt Jones")
                }
            }
            scm {
                url.set("https://github.com/matthewjones372/lark")
                connection.set("scm:git:https://github.com/matthewjones372/lark.git")
                developerConnection.set("scm:git:ssh://git@github.com/matthewjones372/lark.git")
            }
        }
    }

    extensions.configure<PublishingExtension> {
        repositories {
            // `./gradlew publishToMavenLocal` for a local try-out, and
            // `publishAllPublicationsToLocalRepository` for something to
            // inspect without installing it.
            maven {
                name = "local"
                url = rootProject.layout.buildDirectory.dir("repo").get().asFile.toURI()
            }
        }
    }
}
