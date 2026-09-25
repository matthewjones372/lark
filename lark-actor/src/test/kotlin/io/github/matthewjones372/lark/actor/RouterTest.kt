package io.github.matthewjones372.lark.actor

import io.github.matthewjones372.lark.Schedule
import io.github.matthewjones372.lark.flock
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap

private sealed interface Errand

private data class Fetch(val pet: String) : Errand

private data object Topple : Errand

private data object Resign : Errand

/** Writes down, under its own path, every pet it fetched. */
private fun runner(served: ConcurrentHashMap<String, List<String>>) =
    behaviour<Errand, Unit>(Unit) { ctx, _, message ->
        when (message) {
            is Fetch -> {
                served.merge(ctx.self.address.path, listOf(message.pet)) { was, now -> was + now }
                stay()
            }

            Topple -> error("the runner toppled")

            Resign -> stop()
        }
    }

private fun TestActor<*, *, *>.routees(): List<TestActor<*, *, *>> = children.map { it as TestActor<*, *, *> }

class RouterTest {

    private val served = ConcurrentHashMap<String, List<String>>()

    @Test
    fun `a pool of four hands eight messages two to each`() {
        val pool = pool(4) { runner(served) }.test("pool")

        repeat(8) { pool.send(Fetch("pet-$it")) }

        served.values.map { it.size } shouldContainExactly listOf(2, 2, 2, 2)
        served.keys shouldBe (1..4).map { "/user/pool/routee-$it" }.toSet()
    }

    @Test
    fun `hashing sends one key to one routee, always`() {
        val byPet = hashing { errand: Errand -> (errand as? Fetch)?.pet }
        val pool = pool(4, route = byPet) { runner(served) }.test("pool")

        listOf("sam", "bo", "sam", "kit", "bo", "sam").forEach { pool.send(Fetch(it)) }

        listOf("sam", "bo", "kit").forEach { pet -> served.values.count { pet in it } shouldBe 1 }
        served.values.flatten() shouldContainExactlyInAnyOrder listOf("sam", "bo", "sam", "kit", "bo", "sam")
    }

    @Test
    fun `a failed routee restarts alone`() {
        val pool = pool(4, restart = Schedule.recurs(1)) { runner(served) }.test("pool")

        pool.send(Topple)
        repeat(4) { pool.send(Fetch("pet-$it")) }

        pool.routees().map { it.restarts } shouldContainExactly listOf(1, 0, 0, 0)
        served.values.map { it.size } shouldContainExactly listOf(1, 1, 1, 1)
    }

    @Test
    fun `a routee that stops leaves the pool smaller, and a pool with none left stops`() {
        val pool = pool(2) { runner(served) }.test("pool")

        pool.send(Resign)
        pool.send(Fetch("sam"))
        pool.send(Fetch("bo"))
        pool.send(Resign)

        withClue("the first routee resigned, so the second took everything after") {
            served shouldBe mapOf("/user/pool/routee-2" to listOf("sam", "bo"))
        }
        pool.stopped shouldBe true
    }

    @Test
    fun `a group routes over refs already running, with no actor between`() {
        testActors {
            val runners = (1..3).map { spawn("runner-$it", runner(served)) }
            val group = group(runners)

            repeat(6) { group.tell(Fetch("pet-$it")) }

            served.values.map { it.size } shouldContainExactly listOf(2, 2, 2)
        }
    }

    @Test
    fun `on threads, a pool of four hands eight messages two to each`() {
        flock<Nothing, Unit> {
            val pool = spawn("pool", pool(4) { runner(served) })
            repeat(8) { pool.tell(Fetch("pet-$it")) }
            awaitIdle()
        }

        served.values.map { it.size } shouldContainExactly listOf(2, 2, 2, 2)
    }
}
