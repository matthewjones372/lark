// For tests of a surefold service that forks (spec 0042). It depends on surefold and nothing else: a broken order is an
// AssertionError naming its seed, so any test framework reports it.

dependencies {
    api(project(":surefold"))
}
