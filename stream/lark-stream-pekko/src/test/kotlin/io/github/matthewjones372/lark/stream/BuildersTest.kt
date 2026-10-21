package io.github.matthewjones372.lark.stream

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage

/** The builders that start from values already in hand, asserted on a real system. */
class BuildersTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-builders-test")
    }

    private data class Customer(val id: Int, val name: String)

    private data class Missing(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    /** The directory the shape's lookup reads: id 1 is there and nothing else is. */
    private fun lookup(id: Int): Customer? = if (id == 1) Customer(1, "ada") else null

    @Test
    fun `single carries the one element it was given`() {
        val exit = Stream.single(Customer(1, "ada")).runCollect().run(pekko.system).settled()

        exit shouldBe Exit.Done(listOf(Customer(1, "ada")))
    }

    @Test
    fun `a stream from single is a stream like any other`() {
        val exit = Stream.single(Customer(2, "grace"))
            .map { customer -> customer.name.uppercase() }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf("GRACE"))
    }

    @Test
    fun `of carries the elements it names in the order it names them`() {
        val exit = Stream.of("ada", "grace", "alan").runCollect().run(pekko.system).settled()

        exit shouldBe Exit.Done(listOf("ada", "grace", "alan"))
    }

    @Test
    fun `of with no arguments ends the way the empty stream does`() {
        val nothingNamed = Stream.of<String>().runCollect().run(pekko.system).settled()
        val empty = Stream.empty().runCollect().run(pekko.system).settled()

        withClue("naming no elements is the empty stream spelled another way") {
            nothingNamed shouldBe empty
        }
    }

    @Test
    fun `a lookup that finds its value is a stream of the one it found`() {
        val id = 1

        val found = lookup(id)?.let { customer -> Stream.single(customer) } ?: Stream.fail(Missing(id))

        found.runCollect().run(pekko.system).settled() shouldBe Exit.Done(listOf(Customer(1, "ada")))
    }

    /** The choice the element bound forces: absence is a failure with a name, at the lookup. */
    @Test
    fun `a lookup that misses fails with the error it names rather than running empty`() {
        val id = 7

        val found = lookup(id)?.let { customer -> Stream.single(customer) } ?: Stream.fail(Missing(id))

        found.runCollect().run(pekko.system).settled() shouldBe Exit.Failed(Missing(7))
    }
}
