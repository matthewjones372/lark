package io.github.matthewjones372.lark.openfeature

import dev.openfeature.sdk.FlagEvaluationDetails
import dev.openfeature.sdk.Hook
import dev.openfeature.sdk.HookContext
import io.github.matthewjones372.lark.counter
import io.github.matthewjones372.lark.increment
import io.github.matthewjones372.lark.logWarn

/**
 * Every evaluation counted under `openfeature.evaluations`, by flag, variant and reason, and every one
 * that fell back to its default warned about and counted under `openfeature.errors`, by flag and error
 * code. A flag never fails a request, so without this a provider that is down looks like one that said no.
 *
 * Written through lark's `logger` and `metrics` locals, so whatever backend the service bound gets it.
 */
object LarkFeatureHook : Hook<Any> {

    override fun finallyAfter(ctx: HookContext<Any>, details: FlagEvaluationDetails<Any>, hints: Map<String, Any>) {
        val flag = details.flagKey
        counter(
            "openfeature.evaluations",
            "flag" to flag,
            "variant" to details.variant.orEmpty(),
            "reason" to details.reason.orEmpty(),
        ).increment()
        val code = details.errorCode ?: return
        logWarn("flag $flag answered with its default: $code ${details.errorMessage.orEmpty()}".trimEnd())
        counter("openfeature.errors", "flag" to flag, "code" to code.name).increment()
    }
}
