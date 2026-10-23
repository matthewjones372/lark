package io.github.matthewjones372.lark.junit

import io.github.matthewjones372.lark.junit.samples.MixedSuite
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.EngineFilter.includeEngines
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.io.path.readText

/** The JUnit XML a build tool writes from the platform's events, written here by the platform's own listener. */
class JUnitXmlTest {

    @Test
    fun `a pass, a failure and an error are recorded in JUnit XML under the tests' names`(@TempDir reports: Path) {
        val discovery = request().selectors(selectClass(MixedSuite::class.java)).filters(includeEngines("lark")).build()

        val writer = LegacyXmlReportGeneratingListener(reports, PrintWriter(StringWriter()))

        LauncherFactory.create().execute(discovery, writer)

        val xml = reports.resolve("TEST-lark.xml").readText()
        xml shouldContain """<testcase name="passes" classname="${MixedSuite::class.java.name}""""
        xml shouldContain """<testcase name="fails an assertion""""
        xml shouldContain """<failure message="expected 1 but was 2" type="java.lang.AssertionError">"""
        xml shouldContain """<testcase name="throws something else""""
        xml shouldContain """<error message="the database was not there" type="java.lang.IllegalStateException">"""
    }
}
