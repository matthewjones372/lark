package io.github.matthewjones372.lark.actor.remote.kotlinx

import io.github.matthewjones372.lark.junit.Suite
import io.github.matthewjones372.lark.junit.shouldBe
import io.github.matthewjones372.lark.junit.shouldNotBeNull
import io.github.matthewjones372.lark.junit.withClue
import lark.kotlinx.TillOuterClass
import java.io.File

/**
 * What `proto()` writes is pinned as `src/test/proto/lark/kotlinx/till.proto`, which protoc compiles for this test:
 * the classes it generates read what a table writes, and write what a table reads.
 */
object ProtoSuite : Suite() {
    init {
        test("the proto a table makes is the golden file protoc compiles") {
            val path: String? = System.getProperty("lark.actor.remote.kotlinx.golden")
            if (path == null) {
                withClue("the build must pass -Dlark.actor.remote.kotlinx.golden; see its build.gradle.kts") {
                    path.shouldNotBeNull()
                }
            } else {
                withClue(
                    "regenerate the golden file from Kotlinx.proto(\"lark.kotlinx\", tills) if this change is meant",
                ) {
                    Kotlinx.proto("lark.kotlinx", tills) shouldBe File(path).readText()
                }
            }
        }

        test("what a table writes, protoc's classes read") {
            val paid = TillOuterClass.Till.parseFrom(tills.write(Till.Paid(Pence(250), "order-42")))
            val refunded = TillOuterClass.Till.parseFrom(tills.write(Till.Refunded(Pence(100), Reason.Unwanted)))

            (paid.hasPaid() shouldBe true) and
                (paid.paid.amount shouldBe 250L) and
                (paid.paid.reference shouldBe "order-42") and
                (refunded.refunded.reason shouldBe TillOuterClass.Reason.Unwanted) and
                (TillOuterClass.Till.parseFrom(tills.write(Till.Closed)).hasClosed() shouldBe true)
        }

        test("what protoc's classes write, a table reads") {
            val written = TillOuterClass.Till.newBuilder()
                .setRefunded(TillOuterClass.Refunded.newBuilder().setAmount(75).setReason(TillOuterClass.Reason.Faulty))
                .build()
                .toByteArray()

            tills.read(written) shouldBe Till.Refunded(Pence(75), Reason.Faulty)
        }
    }
}
