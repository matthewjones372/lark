package io.github.matthewjones372.lark.actor

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.junit.jupiter.api.Test

internal sealed interface Plain

internal data class Put(val key: String, val reply: Reply<Boolean>) : Plain

internal data object Clear : Plain

internal sealed interface Nested : Plain

internal data class Rename(val from: String, val to: String) : Nested

internal sealed interface Broken

internal data class Later(val then: () -> Unit) : Broken

internal data class Counter(var count: Int) : Broken

internal class Opaque(val key: String) : Broken

internal data object Fine : Broken

class ProtocolTest {

    @Test
    fun `data classes and objects, however deeply sealed, have no faults`() {
        protocolFaults<Plain>().shouldBeEmpty()
    }

    @Test
    fun `a function, a var and a plain class are each named`() {
        protocolFaults<Broken>() shouldContainExactlyInAnyOrder listOf(
            "Later.then holds a function",
            "Counter.count is a var",
            "Opaque is not a data class or an object",
        )
    }
}
