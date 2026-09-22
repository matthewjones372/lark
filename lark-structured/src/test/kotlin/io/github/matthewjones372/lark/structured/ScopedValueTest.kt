package io.github.matthewjones372.lark.structured

import arrow.core.Either
import arrow.core.right
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ScopedValueTest {

    private val tenant: ScopedValue<String> = ScopedValue.newInstance()

    @Test
    fun `a value bound before the scope is read in a fork of a fork, with nothing copied by hand`() {
        val read = ScopedValue.where(tenant, "acme").call(
            ScopedValue.CallableOp<Either<Nothing, String>, RuntimeException> {
                structured<Nothing, String>("tenant") {
                    async { async { tenant.get() }.await() }.await()
                }
            },
        )
        read shouldBe "acme".right()
    }
}
