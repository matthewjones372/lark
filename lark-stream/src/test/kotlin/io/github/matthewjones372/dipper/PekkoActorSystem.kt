package io.github.matthewjones372.dipper

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * An actor system for one test class, started and stopped by JUnit 5.
 *
 * Pekko's own testkit owns it rather than a `val` in the test class: a system
 * started by hand is one a failing test can leave running, and the threads it
 * holds outlive the suite that leaked them.
 */
class PekkoActorSystem(private val name: String) : BeforeAllCallback, AfterAllCallback {

    private lateinit var testKit: ActorTestKit

    /** What `run` takes. */
    val system: ClassicActorSystemProvider get() = testKit.system()

    /** What Pekko's own testkit sinks ask for. */
    val classic: ActorSystem get() = testKit.system().classicSystem()

    override fun beforeAll(context: ExtensionContext) {
        testKit = ActorTestKit.create(name)
    }

    override fun afterAll(context: ExtensionContext) = testKit.shutdownTestKit()
}
