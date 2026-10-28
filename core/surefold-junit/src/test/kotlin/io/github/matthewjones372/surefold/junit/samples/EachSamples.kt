package io.github.matthewjones372.surefold.junit.samples

import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.junit.SharedSuite
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.tag
import io.github.matthewjones372.surefold.resource
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Three cases, one of them wrong, beside a plain test and a group with no cases. */
object EachSuite : Suite() {
    private class Case(val text: String, val expected: Duration)

    init {
        test("a plain test") { expect(true) }
        testEach(
            "a duration parses",
            listOf(Case("1s", 1.seconds), Case("2m", 2.minutes), Case("90m", 1.seconds)),
            aspects = tag("parsing"),
            named = { it.text },
        ) { case -> expect(Duration.parse(case.text) == case.expected) }
        testEach("nothing to check", emptyList<Int>()) { expect(it > 0) }
    }
}

/** Each case is handed the suite's one resource and its own case. */
object SharedEachSuite : SharedSuite<String>() {
    val seen: MutableList<String> = CopyOnWriteArrayList()

    override val shared: Resource<String> = resource { "db" }

    init {
        testEach("a pet is listed", listOf("Nibbles", "Mrs Peel")) { db, pet ->
            seen += "$db $pet"
            expect(db == "db")
        }
    }
}
