package io.github.matthewjones372.lark.stream

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.ensure
import arrow.core.right
import io.github.matthewjones372.lark.parZip
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The scope an element body runs in is a `Raise`, so everything a lark handler
 * writes — `bind`, `ensure`, a fork of its own — is in reach of an operator.
 */
class RaiseInStreamTest {

    companion object {
        @JvmField
        @RegisterExtension
        val pekko = PekkoActorSystem("lark-stream-raise-test")
    }

    private data class NoCustomer(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun lookup(id: Int): Either<NoCustomer, String> =
        if (id == 2) NoCustomer(id).left() else "customer-$id".right()

    @Test
    fun `bind on a Left inside mapOrFail fails the stream with what the Left holds`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id -> lookup(id).bind() }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(NoCustomer(2))
    }

    @Test
    fun `bind on a Right carries its value on as the element`() {
        val exit = Stream.from(listOf(1, 3))
            .mapOrFail { id -> lookup(id).bind() }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf("customer-1", "customer-3"))
    }

    @Test
    fun `ensure fails the stream with the error it names`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id ->
                ensure(id != 2) { NoCustomer(id) }
                "customer-$id"
            }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(NoCustomer(2))
    }

    @Test
    fun `parZip inside mapOrFail runs its branches on forks and combines what they answer`() {
        val virtual = ConcurrentLinkedQueue<Boolean>()

        val exit = Stream.from(listOf(1, 3))
            .mapOrFail { id ->
                parZip(
                    {
                        virtual.add(Thread.currentThread().isVirtual)
                        lookup(id).bind()
                    },
                    {
                        virtual.add(Thread.currentThread().isVirtual)
                        id * 10
                    },
                ) { name, total -> "$name:$total" }
            }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Done(listOf("customer-1:10", "customer-3:30"))
        withClue("every branch of a parZip in an element body runs on a fork of its own") {
            virtual.toList() shouldBe List(4) { true }
        }
    }

    @Test
    fun `a raise in a parZip branch fails the stream with that error`() {
        val exit = Stream.from(listOf(1, 2, 3))
            .mapOrFail { id ->
                parZip({ lookup(id).bind() }, { id * 10 }) { name, total -> "$name:$total" }
            }
            .runCollect()
            .run(pekko.system)
            .settled()

        exit shouldBe Exit.Failed(NoCustomer(2))
    }

    @Test
    fun `raise and fail are one call under two names`() {
        val raised = Stream.from(listOf(1))
            .mapOrFail { id -> raise(NoCustomer(id)) }
            .runCollect()
            .run(pekko.system)
            .settled()

        val failed = Stream.from(listOf(1))
            .mapOrFail { id -> fail(NoCustomer(id)) }
            .runCollect()
            .run(pekko.system)
            .settled()

        raised shouldBe Exit.Failed(NoCustomer(1))
        withClue("fail is the name dipper gave raise, and must end a stream the same way") {
            failed shouldBe raised
        }
    }
}
