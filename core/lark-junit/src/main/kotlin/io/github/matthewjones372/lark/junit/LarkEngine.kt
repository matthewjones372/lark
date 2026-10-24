package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.Deferred
import io.github.matthewjones372.lark.LogLine
import io.github.matthewjones372.lark.capturingLogs
import io.github.matthewjones372.lark.capturingMetrics
import io.github.matthewjones372.lark.flock
import io.github.matthewjones372.lark.use
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.TestTag
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.reporting.ReportEntry
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
        // Ignored tests are skipped before the resource is acquired, so a failed acquisition does not fail them.
        val (ignored, cases) = descriptor.children.filterIsInstance<TestCaseDescriptor>()
            .partition { tests.getValue(it.displayName).aspects.ignored != null }
        ignored.forEach { test -> tests.getValue(test.displayName).aspects.ignored?.let { report.skipped(test, it) } }
        var acquired = false
        val outcome = outcomeOf {
            suite.shared.use { shared ->
                acquired = true
                flock<Nothing, Unit> {
                    val running = mutableListOf<Deferred<Unit>>()
                    cases.forEach { test ->
                        val registered = tests.getValue(test.displayName)
                        if (registered.aspects.alone) running.awaitAll()
                        val started = CountDownLatch(1)
                        running += async {
                            suite.limit.withPermit {
                                report.started(test)
                                started.countDown()
                                val (result, lines) = capturingLogs { logs ->
                                    tellingStories(test.displayName) {
                                        capturingMetrics {
                                            outcomeOf {
                                                registered.aspects.run({ k, v -> report.entry(test, k, v) }) {
                                                    registered.body(shared).verify(test.displayName)
                                                }
                                            }
                                        }
                                    } to logs.all()
                                }
                                report.finished(test, result, lines)
                            }
                        }
                        // Waiting for each test to take its permit starts them in registration order.
                        started.await()
                        if (registered.aspects.alone) running.awaitAll()
                    }
                    running.awaitAll()
                }
            }
        }
        if (acquired) {
            // The tests carry their own results, so a failure left here is from around them, such as the release's.
            listener.executionFinished(descriptor, outcome)
        } else {
            // Each test carries the cause, so it is reported under every test's name and not again for the suite.
            cases.forEach {
                report.started(it)
                report.finished(it, outcome)
            }
            listener.executionFinished(descriptor, TestExecutionResult.successful())
        }
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

    fun skipped(test: TestDescriptor, why: String) = lock.withLock { listener.executionSkipped(test, why) }

    fun entry(test: TestDescriptor, key: String, value: String) =
        lock.withLock { listener.reportingEntryPublished(test, ReportEntry.from(key, value)) }

    /** A failed test's [lines] are published and printed before its result; a passing test's are dropped. */
    fun finished(test: TestDescriptor, result: TestExecutionResult, lines: List<LogLine> = emptyList()) =
        lock.withLock {
            if (result.status == TestExecutionResult.Status.FAILED && lines.isNotEmpty()) {
                val logs = lines.joinToString("\n") { it.rendered() }
                listener.reportingEntryPublished(test, ReportEntry.from("logs", logs))
                println("${test.displayName} logged:\n$logs")
            }
            listener.executionFinished(test, result)
        }
}

/** `LEVEL origin message key=value`, and the cause's class and message where there is one. */
private fun LogLine.rendered(): String =
    (listOfNotNull(level.name.uppercase(), origin, message) + annotations.map { (key, value) -> "$key=$value" })
        .plus(listOfNotNull(cause?.let { "($it)" }))
        .joinToString(" ")

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
            suite.tests.forEach { (name, test) ->
                val id = descriptor.uniqueId.append("test", name)
                descriptor.addChild(TestCaseDescriptor(id, name, type, test.aspects.tags))
            }
            return descriptor
        }
    }
}

internal class TestCaseDescriptor(id: UniqueId, name: String, type: Class<*>, private val tags: Set<TestTag>) :
    AbstractTestDescriptor(id, name, ClassSource.from(type)) {

    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST

    override fun getTags(): Set<TestTag> = tags
}
