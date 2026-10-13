package io.github.matthewjones372.lark.stream

import com.typesafe.config.Config
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.apache.pekko.actor.typed.ActorSystem as TypedActorSystem

/**
 * An actor system for one test class, started and stopped by JUnit 5.
 *
 * Pekko's own testkit owns it rather than a `val` in the test class: a system
 * started by hand is one a failing test can leave running, and the threads it
 * holds outlive the suite that leaked them.
 *
 * [config] is for a class that needs the system itself to differ, such as `ManualTime`'s scheduler.
 */
class PekkoActorSystem(
    private val name: String,
    private val config: Config? = null,
) : BeforeAllCallback, AfterAllCallback {

    private lateinit var testKit: ActorTestKit

    /** What `run` takes. */
    val system: ClassicActorSystemProvider get() = testKit.system()

    /** What Pekko's own testkit sinks ask for. */
    val classic: ActorSystem get() = testKit.system().classicSystem()

    /** What LoggingTestKit asks for; the same system, seen from the side it was created on. */
    val typed: TypedActorSystem<*> get() = testKit.system()

    override fun beforeAll(context: ExtensionContext) {
        testKit = config?.let { ActorTestKit.create(name, it) } ?: ActorTestKit.create(name)
    }

    override fun afterAll(context: ExtensionContext) = testKit.shutdownTestKit()
}
