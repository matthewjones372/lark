package io.github.matthewjones372.lark.app.typesafe

import io.github.matthewjones372.lark.assert.shouldBe
import org.junit.jupiter.api.Test

class SecretNamesTest {

    @Test
    fun `the compiler checker's secret names are Secrets default's, no more and no fewer`() {
        // Both are private to their files, so each is read where its class file holds it.
        val telling = Secrets::class.java.getDeclaredField("TELLING").apply { isAccessible = true }.get(null)
        val checked = Class.forName("io.github.matthewjones372.lark.app.compiler.HandRolledKt")
            .getDeclaredField("secretNames").apply { isAccessible = true }.get(null)

        checked shouldBe telling
    }
}
