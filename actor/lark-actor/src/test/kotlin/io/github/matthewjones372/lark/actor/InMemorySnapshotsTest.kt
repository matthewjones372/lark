package io.github.matthewjones372.lark.actor

import arrow.core.right
import io.github.matthewjones372.lark.flock
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
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

        val given = flock<Nothing, Any> {
            snapshots(store)
            snapshots() shouldBeSameInstanceAs store
            spawn("which", which()).ask(1.minutes) { Which(it) }
        }
        val none = flock<Nothing, Any> {
            snapshots().shouldBeNull()
            spawn("which", which()).ask(1.minutes) { Which(it) }
        }

        given shouldBe store.right().right()
        none shouldBe "none".right().right()
    }

    @Test
    fun `a test's actors see the test's store, which the test can read`() {
        testActors {
            val which = spawn("which", which())

            which.ask<Any> { Which(it) }.getOrNull() shouldBeSameInstanceAs snapshots
        }
    }
}
