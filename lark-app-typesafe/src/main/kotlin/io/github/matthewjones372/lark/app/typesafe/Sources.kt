package io.github.matthewjones372.lark.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.overriding
import io.github.matthewjones372.lark.app.single
import java.nio.file.Path

/** What a service reads at start-up: `application.conf`, its reference, and the system properties. */
fun loadedConfig(): Module = single<Config> { ConfigFactory.load() }

/** A document the assembly already parsed — the one a `choosing` read to pick its modules. */
fun configOf(config: Config): Module = single<Config> { config }

/** A document written where the test is, for a case that would rather not have a file at all. */
fun configOf(hocon: String): Module = single<Config> { ConfigFactory.parseString(hocon).resolve() }

/** A file on the classpath — `test.conf` beside the test, rather than in the service's own. */
fun configFromResource(name: String): Module =
    single<Config> { ConfigFactory.parseResources(name).resolve() }

/** A file on disk, for a fixture a test writes and a container reads. */
fun configFromFile(path: Path): Module = single<Config> { ConfigFactory.parseFile(path.toFile()).resolve() }

/**
 * This graph with [hocon] on top of the configuration it already has.
 *
 * The two-key case, which is most of them: a test that wants a random port and a fast poll should not
 * have to restate the database, the broker and everything else the file says. [fallback] is what the
 * service itself would have read, and anything [hocon] does not mention comes from there.
 */
fun Module.overridingConfig(hocon: String, fallback: Config = ConfigFactory.load()): Module =
    overriding(single<Config> { ConfigFactory.parseString(hocon).withFallback(fallback).resolve() })
