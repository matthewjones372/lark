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
include("lark-pekko")
module("stream", "lark-stream")
module("stream", "lark-stream-pekko")
module("stream", "lark-stream-forks")
module("stream", "lark-stream-test")
module("core", "lark-test")
module("stream", "lark-stream-actors")
module("stream", "lark-stream-parity")
include("lark-app")
include("lark-app-pekko")
include("lark-otel")
include("lark-slf4j")
include("lark-micrometer")
include("lark-app-liquibase")
include("lark-app-typesafe")
include("lark-app-gradle")
include("lark-app-compiler")
module("core", "lark-structured")
module("bench", "lark-stream-benchmarks")
include("lark-kafka")
include("lark-kafka-pekko")
include("lark-actor")
include("lark-app-actor")
include("lark-app-cluster")
include("lark-actor-remote")
include("lark-actor-remote-protobuf")
include("lark-actor-remote-avro")
include("lark-actor-remote-kotlinx")
include("lark-actor-journal-jdbc")
include("lark-actor-projection")
include("lark-cluster")
include("lark-cluster-kubernetes")
include("lark-cluster-aws")
module("bench", "lark-actor-benchmarks")
include("lark-bank")
