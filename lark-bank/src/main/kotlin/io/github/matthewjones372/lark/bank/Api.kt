package io.github.matthewjones372.lark.bank

import arrow.core.Either
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors

/** What a request comes to: a status and a JSON body. */
internal data class Answer(val status: Int, val body: Map<String, Any>)

private val ids = Regex("[A-Za-z0-9_-]{1,64}")

private fun failed(status: Int, why: String) = Answer(status, mapOf("error" to why))

private fun <A> Either<*, A>.or(status: Int, answer: (A) -> Answer): Answer = fold({ failed(status, "$it") }, answer)

/**
 * The consumer's JSON API on one node, served by the JDK's `HttpServer` on [port], each exchange on a virtual thread
 * of its own. There is no library, so the routes are a `when` over the method and the path's parts.
 */
internal class Api(private val node: BankNode, port: Int) : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(port), 0).apply {
        executor = Executors.newVirtualThreadPerTaskExecutor()
        createContext("/api/") { exchange -> exchange.use { respond(it, answer(it)) } }
        start()
    }

    val port: Int get() = server.address.port

    private fun answer(exchange: HttpExchange): Answer {
        val path = exchange.requestURI.path.removePrefix("/api/").split("/")
        val body = if (exchange.requestMethod ==
            "POST"
        ) Json.read(exchange.requestBody.readAllBytes().decodeToString()) else null
        return when {
            exchange.requestMethod == "POST" && body == null -> failed(400, "the body is not a flat JSON object")
            exchange.requestMethod == "POST" && path == listOf("accounts") -> open(body.orEmpty())
            exchange.requestMethod == "POST" && path == listOf("transfers") -> transfer(body.orEmpty())
            exchange.requestMethod == "GET" && path.size == 2 && path[0] == "accounts" -> account(path[1])
            exchange.requestMethod == "GET" && path.size == 2 && path[0] == "transfers" -> status(path[1])
            else -> failed(404, "no route for ${exchange.requestMethod} ${exchange.requestURI.path}")
        }
    }

    private fun open(body: Map<String, String>): Answer {
        val id =
            body["id"]?.takeIf(ids::matches) ?: return failed(400, "an account id is 1 to 64 letters, digits, - or _")
        val pence = body["amount"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return failed(400, "amount is a whole number")
        return node.open(id, pence).or(503) { Answer(200, mapOf("id" to id, "balance" to it)) }
    }

    private fun account(id: String): Answer = node.statement(id).or(503) { statement ->
        if (!statement.open) return@or failed(404, "no account $id")
        val movements = statement.movements.map { mapOf("transfer" to it.transfer, "amount" to it.pence) }
        Answer(200, mapOf("id" to id, "balance" to statement.balance, "movements" to movements))
    }

    private fun transfer(body: Map<String, String>): Answer {
        val from = body["from"]?.takeIf(ids::matches) ?: return failed(400, "from names no account")
        val to = body["to"]?.takeIf { ids.matches(it) && it != from } ?: return failed(400, "to names no other account")
        val pence = body["amount"]?.toLongOrNull()?.takeIf { it > 0 } ?: return failed(400, "amount is more than 0")
        if (node.statement(to).getOrNull()?.open != true) return failed(404, "no account $to")
        val id = "t-${UUID.randomUUID()}"
        return node.transfer(id, from, to, pence).or(503) { Answer(202, mapOf("id" to id)) }
    }

    private fun status(id: String): Answer = node.status(id).or(503) { phase ->
        if (phase ==
            Phase.New.name
        ) failed(404, "no transfer $id") else Answer(200, mapOf("id" to id, "status" to phase))
    }

    private fun respond(exchange: HttpExchange, answer: Answer) {
        val bytes = Json.write(answer.body).encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(answer.status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    override fun close() = server.stop(0)
}
