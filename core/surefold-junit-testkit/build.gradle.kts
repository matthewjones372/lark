// Checks on what a suite's run reported (spec 0204): which tests succeeded, failed or were skipped, and what a failed
// one threw, read from JUnit's EngineTestKit. It depends on junit-platform-testkit and opentest4j, and nothing else.

dependencies {
    api("org.junit.platform:junit-platform-testkit:6.1.3")
    api("org.opentest4j:opentest4j:1.3.0")

    testImplementation(project(":surefold-junit"))
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    // Suites that fail on purpose, run only by the tests that select them, never by this task.
    exclude("**/testkit/samples/**")
}
