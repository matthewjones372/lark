package io.github.matthewjones372.lark.structured

import io.github.matthewjones372.lark.fail.Either
import io.github.matthewjones372.lark.fail.attempt
import io.github.matthewjones372.lark.fail.right
import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe

object ScopedValueSuite : Suite() {
    private val tenant: ScopedValue<String> = ScopedValue.newInstance()

    init {
        test("a value bound before forking is read in a branch of a branch, with nothing copied by hand") {
            val read = ScopedValue.where(tenant, "acme").call(
                ScopedValue.CallableOp<Either<Nothing, String>, RuntimeException> {
                    attempt {
                        val inner = { parZip({ tenant.get() }, { tenant.get() }) { a, b -> "$a $b" } }
                        parZip({ inner() }, { tenant.get() }) { a, b -> "$a $b" }
                    }
                },
            )
            read shouldBe "acme acme acme".right()
        }
    }
}
