package io.github.matthewjones372.lark.structured

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.right
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ScopedValueTest {

    private val tenant: ScopedValue<String> = ScopedValue.newInstance()

    @Test
    fun `a value bound before forking is read in a branch of a branch, with nothing copied by hand`() {
        val read = ScopedValue.where(tenant, "acme").call(
            ScopedValue.CallableOp<Either<Nothing, String>, RuntimeException> {
                either {
                    val inner = { parZip({ tenant.get() }, { tenant.get() }) { a, b -> "$a $b" } }
                    parZip({ inner() }, { tenant.get() }) { a, b -> "$a $b" }
                }
            },
        )
        read shouldBe "acme acme acme".right()
    }
}
