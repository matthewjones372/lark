package io.github.matthewjones372.surefold.actor

import io.github.matthewjones372.surefold.assert.shouldBeNull
import io.github.matthewjones372.surefold.assert.shouldBeRight
import io.github.matthewjones372.surefold.assert.shouldBeSameInstanceAs
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.scope
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.minutes

class InMemorySnapshotsTest : SnapshotContract() {
    override fun store(): SnapshotStore = InMemorySnapshots()
}

/** Answers the snapshot store its actor sees. */
private data class Which(val reply: Reply<Any>)

private fun which() = behaviour<Which, Unit>(Unit) { ctx, _, which ->
    stay().also { which.reply(ctx.snapshots ?: "none") }
}

class SnapshotsTest {

    @Test
    fun `on threads, an actor sees its flock's store, and a flock given none has none`() {
        val store = InMemorySnapshots()

        val given = scope {
            snapshots(store)
            snapshots() shouldBeSameInstanceAs store
            spawn("which", which()).ask(1.minutes) { Which(it) }
        }
        val none = scope {
            snapshots().shouldBeNull()
            spawn("which", which()).ask(1.minutes) { Which(it) }
        }

        given shouldBeRight store
        none shouldBeRight "none"
    }

    @Test
    fun `a test's actors see the test's store, which the test can read`() {
        testActors {
            val which = spawn("which", which())

            which.ask<Any> { Which(it) }.getOrNull() shouldBeSameInstanceAs snapshots
        }
    }
}
