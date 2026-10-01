package io.github.matthewjones372.lark.bank

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** The bank's JSON: a field a route does not know is ignored, so a page may send more than the route reads. */
internal val json = Json { ignoreUnknownKeys = true }

/** [body] as an [A], or null when it is not JSON or not that shape. */
internal inline fun <reified A> decoded(body: String): A? = try {
    json.decodeFromString<A>(body)
} catch (_: SerializationException) {
    null
}

@Serializable
internal data class OpenBody(val id: String, val amount: Long)

@Serializable
internal data class TransferBody(val from: String, val to: String, val amount: Long)

@Serializable
internal data class LoadBody(val on: Boolean)

@Serializable
internal data class CrashBody(val node: String)

@Serializable
internal data class ErrorBody(val error: String)

@Serializable
internal data class BalanceBody(val id: String, val balance: Long)

@Serializable
internal data class MovementBody(val transfer: String, val amount: Long)

@Serializable
internal data class AccountBody(val id: String, val balance: Long, val movements: List<MovementBody>)

@Serializable
internal data class AcceptedBody(val id: String)

@Serializable
internal data class LoadedBody(val load: Boolean)

@Serializable
internal data class CrashedBody(val crashed: String)

@Serializable
internal data class StatusBody(val id: String, val status: String)
