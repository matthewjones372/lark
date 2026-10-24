package io.github.matthewjones372.lark.openfeature

import dev.openfeature.sdk.Client
import dev.openfeature.sdk.FlagEvaluationDetails
import dev.openfeature.sdk.OpenFeatureAPI
import io.github.matthewjones372.lark.LarkLocal
import io.github.matthewjones372.lark.larkLocal

/**
 * The client a [Flag] is read through: the default domain's, unless a test or a service with several
 * providers binds another with `locally`. A fork reads the one its opener bound.
 */
val featureClient: LarkLocal<Client> = larkLocal { OpenFeatureAPI.getInstance().client }

/**
 * A flag declared once: its key, its type and the default it answers when it cannot be evaluated, which
 * 0180's hook then reports. Read with [value] or [details] against [featureClient] and the context
 * `evaluatedFor` bound, so a call site passes nothing in.
 */
class Flag<A : Any> private constructor(
    val key: String,
    val default: A,
    private val read: Client.(String, A) -> FlagEvaluationDetails<A>,
) {
    /** The flag's value for whoever is bound, or [default] if it cannot be evaluated. */
    fun value(): A = details().value ?: default

    /** The evaluation behind [value]: its variant, its reason, and the error code if it fell back. */
    fun details(): FlagEvaluationDetails<A> = featureClient.get().read(key, default)

    companion object {
        fun boolean(key: String, default: Boolean): Flag<Boolean> =
            Flag(key, default) { k, d -> getBooleanDetails(k, d) }

        fun string(key: String, default: String): Flag<String> =
            Flag(key, default) { k, d -> getStringDetails(k, d) }

        fun int(key: String, default: Int): Flag<Int> =
            Flag(key, default) { k, d -> getIntegerDetails(k, d) }

        fun double(key: String, default: Double): Flag<Double> =
            Flag(key, default) { k, d -> getDoubleDetails(k, d) }
    }
}
