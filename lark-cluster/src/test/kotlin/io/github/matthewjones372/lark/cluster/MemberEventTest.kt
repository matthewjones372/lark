package io.github.matthewjones372.lark.cluster

import io.github.matthewjones372.lark.actor.remote.Node
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

private fun member(n: Int, status: Status = Status.Up, uid: Long = n.toLong()) =
    Member(Node("n$n", "10.0.0.$n", 25520), uid, status, n)

private fun view(vararg members: Member, unreachable: Set<Int> = emptySet()) =
    View(members.toList(), unreachable.mapTo(mutableSetOf()) { member(it).node }, members.firstOrNull()?.node)

class MemberEventTest {

    @Test
    fun `a member moved to Up is said to be Up, and a joiner is not`() {
        val before = view(member(1), member(2, Status.Joining))
        val after = view(member(1), member(2), member(3, Status.Joining))

        changes(before, after) shouldContainExactly listOf(MemberEvent.Up(member(2)))
    }

    @Test
    fun `a member that becomes unreachable is said to be, and again once it is reachable`() {
        val all = arrayOf(member(1), member(2), member(3))

        changes(view(*all), view(*all, unreachable = setOf(3))) shouldContainExactly
            listOf(MemberEvent.Unreachable(member(3)))
        changes(view(*all, unreachable = setOf(3)), view(*all)) shouldContainExactly
            listOf(MemberEvent.Reachable(member(3)))
    }

    @Test
    fun `a member that is gone from the view is removed, and not said to be reachable on its way out`() {
        changes(view(member(1), member(2), unreachable = setOf(2)), view(member(1))) shouldContainExactly
            listOf(MemberEvent.Removed(member(2)))
    }

    @Test
    fun `a node that restarts is a removed member and a new one Up, not a member that stayed`() {
        changes(view(member(1), member(2)), view(member(1), member(2, uid = 22))) shouldContainExactly
            listOf(MemberEvent.Up(member(2, uid = 22)), MemberEvent.Removed(member(2)))
    }

    @Test
    fun `a view that has not changed says nothing`() {
        changes(view(member(1), member(2)), view(member(1), member(2))).shouldBeEmpty()
    }
}
