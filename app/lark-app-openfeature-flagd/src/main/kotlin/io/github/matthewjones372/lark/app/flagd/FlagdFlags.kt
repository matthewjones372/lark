package io.github.matthewjones372.lark.app.flagd

import com.typesafe.config.Config
import com.typesafe.config.ConfigRenderOptions
import dev.openfeature.contrib.providers.flagd.FlagdOptions
import dev.openfeature.contrib.providers.flagd.FlagdProvider
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.QueuePayload
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.QueuePayloadType
import dev.openfeature.contrib.providers.flagd.resolver.process.storage.connector.QueueSource
import dev.openfeature.contrib.tools.flagd.core.model.FlagParser
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.openfeature.openFeature
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import dev.openfeature.contrib.providers.flagd.Config as FlagdConfig

/**
 * The flags at [path] of the graph's `Config`, written in flagd's schema, evaluated in-process by flagd and set as
 * the graph's OpenFeature provider. HOCON is a superset of JSON, so a flagd definition is already a valid section;
 * a test overrides it like any other. A section flagd rejects refuses the start, naming where it came from.
 */
fun flagdFlags(path: String): Module = openFeature { config: Config ->
    if (!config.hasPath(path)) refuse("there is no flags section at $path")
    val origin = config.getValue(path).origin().description()
    val flags = """{"flags":${config.getConfig(path).root().render(ConfigRenderOptions.concise())}}"""
    try {
        FlagParser.parseString(flags, true)
    } catch (rejected: IllegalArgumentException) {
        refuse("flagd rejected the flags at $path ($origin): ${rejected.message}")
    }
    FlagdProvider(
        FlagdOptions.builder().resolverType(FlagdConfig.Resolver.IN_PROCESS).customConnector(Once(flags)).build(),
    )
}

/** What flagd's in-process resolver reads its flags from: these, once, since a change is a redeploy. */
private class Once(flags: String) : QueueSource {
    private val queue = LinkedBlockingQueue(listOf(QueuePayload(QueuePayloadType.DATA, flags)))

    override fun init() = Unit

    override fun getStreamQueue(): BlockingQueue<QueuePayload> = queue

    override fun shutdown() {
        queue.offer(QueuePayload.SHUTDOWN)
    }
}
