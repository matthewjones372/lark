package io.github.matthewjones372.lark.app

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import kotlin.reflect.typeOf

class SingleOfTest {

    private class Wiring

    private class Socket(val wiring: Wiring)

    private interface Reads {
        fun read(): String
    }

    private class Reader(val socket: Socket, val wiring: Wiring) : Reads {
        override fun read() = "read"
    }

    private class Whole(val reads: Reads)

    @Test
    fun `a constructor names the key and the dependencies`() {
        val app = singleOf(::Wiring) + singleOf(::Socket) + singleOf(::Reader)

        val reader = testApp(app) { reader: Reader -> reader }

        withClue("the constructor said DataSource and Log; nothing had to say it twice") {
            reader.socket.wiring shouldBe reader.wiring
        }
    }

    @Test
    fun `boundTo keys a node as the interface everything else asks for`() {
        val app = singleOf(::Wiring) + singleOf(::Socket) +
            singleOf(::Reader).boundTo<Reads>() +
            singleOf(::Whole)

        testApp(app) { whole: Whole -> whole.reads.read() } shouldBe "read"
    }

    @Test
    fun `the node keyed as the interface is the only one`() {
        val app = singleOf(::Wiring) + singleOf(::Socket) + singleOf(::Reader).boundTo<Reads>()

        val plan = app.validate().getOrNull().shouldNotBeNull()

        withClue("re-keying moves the node rather than adding a second") {
            plan.layers.flatten().contains(typeOf<Reader>()) shouldBe false
            plan.layers.flatten().contains(typeOf<Reads>()) shouldBe true
        }
    }

    @Test
    fun `a probe follows the key it was asked of`() {
        val app = singleOf(::Wiring) + singleOf(::Socket) +
            singleOf(::Reader)
                .probe("reader", timeout = kotlin.time.Duration.parse("2s")) { reads: Reader -> reads.read() == "read" }
                .boundTo<Reads>() +
            singleOf(::Whole)

        testApp(app) { whole: Whole -> whole.reads.read() } shouldBe "read"
    }

    @Test
    fun `re-keying a module of several nodes is refused, and says how many`() {
        val failure = shouldThrow<IllegalArgumentException> {
            (singleOf(::Wiring) + singleOf(::Socket)).boundTo<Reads>()
        }

        failure.message.orEmpty() shouldContain "holds 2"
    }

    @Test
    fun `a constructor that takes nothing needs its type argument`() {
        val app = singleOf<Wiring> { Wiring() }

        app.validate().getOrNull().shouldNotBeNull().layers.flatten() shouldBe listOf(typeOf<Wiring>())
    }
}

private class Borrowed {
    var given = false

    fun hand() {
        given = true
    }
}

/** A constructor with a teardown that is not `close`, which is most of them. */
class SingleOfReleaseTest {

    @Test
    fun `what a constructor built is given back, by whatever the method is called`() {
        val app = singleOf(::Borrowed, Borrowed::hand)

        val borrowed = testApp(app) { borrowed: Borrowed -> borrowed }

        withClue("the release ran on the way out, not while the block held it") {
            borrowed.given shouldBe true
        }
    }

    @Test
    fun `it is given back when a later node refuses`() {
        val taken = java.util.concurrent.atomic.AtomicReference<Borrowed>()
        val app = singleOf(::Borrowed, Borrowed::hand) +
            single { borrowed: Borrowed -> taken.set(borrowed); "kept" }

        app.use { _: String -> }.getOrNull().shouldNotBeNull()

        taken.get().given shouldBe true
    }
}
