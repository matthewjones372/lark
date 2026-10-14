// Where a Java toolchain comes from when the machine does not already have it.
// Gradle deprecated auto-provisioning without a declared resolver, so the build
// says which one it uses rather than relying on a default that is going away.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "lark"

// Each module lives in its family's folder but keeps a flat project path, so `:lark-stream:test`,
// `project(":lark-stream")` and the published coordinates do not depend on where the folder is.
fun module(group: String, name: String) {
    include(name)
    project(":$name").projectDir = file("$group/$name")
}

module("core", "lark")
module("core", "lark-test")
module("core", "lark-structured")

module("stream", "lark-stream")
module("stream", "lark-stream-pekko")
module("stream", "lark-stream-forks")
module("stream", "lark-stream-test")
module("stream", "lark-stream-actors")
module("stream", "lark-stream-parity")

module("actor", "lark-actor")
module("actor", "lark-actor-remote")
module("actor", "lark-actor-remote-protobuf")
module("actor", "lark-actor-remote-avro")
module("actor", "lark-actor-remote-kotlinx")
module("actor", "lark-actor-journal-jdbc")
module("actor", "lark-actor-projection")

module("cluster", "lark-cluster")
module("cluster", "lark-cluster-kubernetes")
module("cluster", "lark-cluster-aws")

module("app", "lark-app")
module("app", "lark-app-pekko")
module("app", "lark-app-liquibase")
module("app", "lark-app-typesafe")
module("app", "lark-app-gradle")
module("app", "lark-app-compiler")
module("app", "lark-app-actor")
module("app", "lark-app-cluster")

module("integrations", "lark-pekko")
module("integrations", "lark-otel")
module("integrations", "lark-slf4j")
module("integrations", "lark-micrometer")
module("integrations", "lark-kafka")
module("integrations", "lark-kafka-pekko")

module("bench", "lark-stream-benchmarks")
module("bench", "lark-actor-benchmarks")

module("examples", "lark-bank")
