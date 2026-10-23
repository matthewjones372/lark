package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.Deferred
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.use
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.discovery.EngineDiscoveryRequestResolver
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.Match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution
import java.lang.reflect.Modifier
import java.util.Optional
import java.util.concurrent.CountDownLatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Runs every [Suite] object the platform's selectors reach, one suite at a time and its tests as forks. */
class LarkEngine : TestEngine {

    override fun getId(): String = "lark"

    override fun discover(request: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor =
        EngineDescriptor(uniqueId, "Lark").also { resolver.resolve(request, it) }

    override fun execute(request: ExecutionRequest) {
        val listener = request.engineExecutionListener
        val engine = request.rootTestDescriptor
        listener.executionStarted(engine)
        engine.children.filterIsInstance<SuiteDescriptor>().forEach { run(it.suite, it, listener) }
        listener.executionFinished(engine, TestExecutionResult.successful())
    }

    private fun <R> run(suite: Suite<R>, descriptor: SuiteDescriptor, listener: EngineExecutionListener) {
        listener.executionStarted(descriptor)
        val tests = suite.tests
        val report = Report(listener)
        val outcome = outcomeOf {
            suite.shared.use { shared ->
                flock<Nothing, Unit> {
                    val running = mutableListOf<Deferred<Unit>>()
                    descriptor.children.filterIsInstance<TestCaseDescriptor>().forEach { test ->
                        val registered = tests.getValue(test.displayName)
                        if (registered.alone) running.awaitAll()
                        val started = CountDownLatch(1)
                        running += async {
                            suite.limit.withPermit {
                                report.started(test)
                                started.countDown()
                                report.finished(test, outcomeOf { registered.body(shared) })
                            }
                        }
                        // Waiting for each test to take its permit starts them in registration order.
                        started.await()
                        if (registered.alone) running.awaitAll()
                    }
                    running.awaitAll()
                }
            }
        }
        listener.executionFinished(descriptor, outcome)
    }

    private fun MutableList<Deferred<Unit>>.awaitAll() {
        forEach { it.await() }
        clear()
    }

    /** A test's throw is its result, of whatever type, as Jupiter's is; only the VM's own errors end the run. */
    private fun outcomeOf(block: () -> Unit): TestExecutionResult = try {
        block()
        TestExecutionResult.successful()
    } catch (fatal: VirtualMachineError) {
        throw fatal
    } catch (t: Throwable) {
        TestExecutionResult.failed(t)
    }
}

/** Forks report from their own threads, and a listener need not be thread-safe. */
private class Report(private val listener: EngineExecutionListener) {
    private val lock = ReentrantLock()

    fun started(test: TestDescriptor) = lock.withLock { listener.executionStarted(test) }

    fun finished(test: TestDescriptor, result: TestExecutionResult) =
        lock.withLock { listener.executionFinished(test, result) }
}

/** A Kotlin `object`: its one instance is the static `INSTANCE` field, read without initialising the class. */
private fun isSuiteObject(type: Class<*>): Boolean = Suite::class.java.isAssignableFrom(type) &&
    type.declaredFields.any { it.name == "INSTANCE" && it.type == type && Modifier.isStatic(it.modifiers) }

private object SuiteResolver : SelectorResolver {
    override fun resolve(selector: ClassSelector, context: SelectorResolver.Context): Resolution {
        val type = selector.getJavaClass()
        if (!isSuiteObject(type)) return Resolution.unresolved()
        return context.addToParent { parent -> Optional.of(SuiteDescriptor.of(parent.uniqueId, type)) }
            .map { Resolution.match(Match.exact(it)) }
            .orElse(Resolution.unresolved())
    }
}

private val resolver = EngineDiscoveryRequestResolver.builder<EngineDescriptor>()
    .addClassContainerSelectorResolver { isSuiteObject(it) }
    .addSelectorResolver(SuiteResolver)
    .build()

internal class SuiteDescriptor private constructor(id: UniqueId, type: Class<*>, val suite: Suite<*>) :
    AbstractTestDescriptor(id, type.simpleName, ClassSource.from(type)) {

    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER

    companion object {
        fun of(parent: UniqueId, type: Class<*>): SuiteDescriptor {
            val suite = type.getField("INSTANCE").get(null) as Suite<*>
            val descriptor = SuiteDescriptor(parent.append("suite", type.name), type, suite)
            suite.tests.keys.forEach { name ->
                descriptor.addChild(TestCaseDescriptor(descriptor.uniqueId.append("test", name), name, type))
            }
            return descriptor
        }
    }
}

internal class TestCaseDescriptor(id: UniqueId, name: String, type: Class<*>) :
    AbstractTestDescriptor(id, name, ClassSource.from(type)) {

    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST
}
