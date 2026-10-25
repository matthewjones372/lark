package io.github.matthewjones372.lark.junit.samples

import io.github.matthewjones372.lark.Resource
import io.github.matthewjones372.lark.junit.SharedSuite
import io.github.matthewjones372.lark.junit.expect
import io.github.matthewjones372.lark.resource

/** Spec 0175's example: a database that saves an order twice. ExpectDiagramTest holds its lines to a golden. */
object OrderSuite : SharedSuite<Database>(concurrent = false) {
    override val shared: Resource<Database> = resource { Database() }

    init {
        test("an order is saved once") { db ->
            repeat(2) { db.save("order-1") }
            expect(db.count() == 1) and expect(db.find("order-1") == "order-1")
        }
    }
}

class Database {
    private val orders = mutableListOf<String>()

    fun save(order: String) {
        orders += order
    }

    fun count(): Int = orders.size

    fun find(id: String): String? = orders.firstOrNull { it == id }

    override fun toString(): String = "Database(orders=${orders.size})"
}
