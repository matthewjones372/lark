// Where a Java toolchain comes from when the machine does not already have it.
// Gradle deprecated auto-provisioning without a declared resolver, so the build
// says which one it uses rather than relying on a default that is going away.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "lark"
include("lark")
include("lark-pekko")
include("lark-stream")
include("lark-stream-pekko")
include("lark-stream-forks")
include("lark-stream-parity")
include("lark-app")
include("lark-app-pekko")
include("lark-otel")
include("lark-slf4j")
include("lark-micrometer")
include("lark-app-liquibase")
include("lark-app-typesafe")
include("lark-app-gradle")
include("lark-app-compiler")
include("lark-structured")
include("lark-stream-benchmarks")
