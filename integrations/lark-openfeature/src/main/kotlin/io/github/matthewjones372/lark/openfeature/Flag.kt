package io.github.matthewjones372.lark.openfeature

import dev.openfeature.sdk.Client
import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.FlagEvaluationDetails
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.Value
import io.github.matthewjones372.lark.LarkLocal
import io.github.matthewjones372.lark.larkLocal
import java.time.Instant
import kotlin.time.Duration

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

        /** An integer flag read as a [Long], through the SDK's own long evaluation. */
        fun long(key: String, default: Long): Flag<Long> =
            Flag(key, default) { k, d -> getLongDetails(k, d) }

        /** A string flag read as a [Duration]: `"5s"`, `"1m 30s"` or ISO-8601's `"PT5S"`. */
        fun duration(key: String, default: Duration): Flag<Duration> = fromString(key, default, Duration::parse)

        /** A string flag read as an ISO-8601 [Instant]. */
        fun instant(key: String, default: Instant): Flag<Instant> = fromString(key, default, Instant::parse)

        /** A string flag read as one of [E]'s constants, by exact name: a wrong case is a typo, not a match. */
        inline fun <reified E : Enum<E>> enum(key: String, default: E): Flag<E> =
            fromString(key, default) { name -> enumValueOf<E>(name) }

        /** An object flag read through [decode], which the caller writes against the SDK's [Value]. */
        fun <A : Any> of(key: String, default: A, decode: (Value) -> A): Flag<A> =
            Flag(key, default) { k, d -> decoded(getObjectDetails(k, Value()), d, decode) }

        @PublishedApi
        internal fun <A : Any> fromString(key: String, default: A, decode: (String) -> A): Flag<A> =
            Flag(key, default) { k, d -> decoded(getStringDetails(k, d.toString()), d, decode) }

        /**
         * [raw] decoded, or [default] when the SDK already fell back, which its hook has reported. A decode
         * that throws answers [default] with `PARSE_ERROR`, reported here since the hook has already run.
         */
        @Suppress("TooGenericExceptionCaught") // a decode is the caller's function; whatever it throws is a bad value
        private fun <R, A : Any> decoded(raw: FlagEvaluationDetails<R>, default: A, decode: (R) -> A) =
            if (raw.errorCode != null) {
                raw.carrying(default, raw.errorCode, raw.errorMessage)
            } else {
                try {
                    raw.carrying(decode(raw.value), null, null)
                } catch (thrown: Exception) {
                    val message = "${raw.value} did not decode: ${thrown.message ?: thrown::class.simpleName}"
                    fellBack(raw.flagKey, ErrorCode.PARSE_ERROR, message)
                    raw.carrying(default, ErrorCode.PARSE_ERROR, message)
                }
            }

        private fun <R, A> FlagEvaluationDetails<R>.carrying(value: A, code: ErrorCode?, message: String?) =
            FlagEvaluationDetails.builder<A>()
                .flagKey(flagKey)
                .value(value)
                .variant(variant)
                .reason(if (code == ErrorCode.PARSE_ERROR) "ERROR" else reason)
                .errorCode(code)
                .errorMessage(message)
                .flagMetadata(flagMetadata)
                .build()
    }
}
