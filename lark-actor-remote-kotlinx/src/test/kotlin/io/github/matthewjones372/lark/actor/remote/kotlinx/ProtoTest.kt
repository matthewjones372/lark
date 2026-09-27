package io.github.matthewjones372.lark.actor.remote.kotlinx

import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import lark.kotlinx.TillOuterClass
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What `proto()` writes is pinned as `src/test/proto/lark/kotlinx/till.proto`, which protoc compiles for this test:
 * the classes it generates read what a table writes, and write what a table reads.
 */
class ProtoTest {

    private fun golden(): String {
        val path = System.getProperty("lark.actor.remote.kotlinx.golden")
        withClue("the build must pass -Dlark.actor.remote.kotlinx.golden; see its build.gradle.kts") {
            path.shouldNotBeNull()
        }
        return File(path!!).readText()
    }

    @Test
    fun `the proto a table makes is the golden file protoc compiles`() {
        withClue("regenerate the golden file from Kotlinx.proto(\"lark.kotlinx\", tills) if this change is meant") {
            Kotlinx.proto("lark.kotlinx", tills) shouldBe golden()
        }
    }

    @Test
    fun `what a table writes, protoc's classes read`() {
        val paid = TillOuterClass.Till.parseFrom(tills.write(Till.Paid(Pence(250), "order-42")))
        paid.hasPaid() shouldBe true
        paid.paid.amount shouldBe 250L
        paid.paid.reference shouldBe "order-42"

        val refunded = TillOuterClass.Till.parseFrom(tills.write(Till.Refunded(Pence(100), Reason.Unwanted)))
        refunded.refunded.reason shouldBe TillOuterClass.Reason.Unwanted

        TillOuterClass.Till.parseFrom(tills.write(Till.Closed)).hasClosed() shouldBe true
    }

    @Test
    fun `what protoc's classes write, a table reads`() {
        val written = TillOuterClass.Till.newBuilder()
            .setRefunded(TillOuterClass.Refunded.newBuilder().setAmount(75).setReason(TillOuterClass.Reason.Faulty))
            .build()
            .toByteArray()

        tills.read(written) shouldBe Till.Refunded(Pence(75), Reason.Faulty)
    }
}
