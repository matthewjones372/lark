package io.github.matthewjones372.lark.app

import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/** What the check found: what to print, and whether the build should stop. */
data class Checked(val report: String, val failed: Boolean)

/**
 * Every [LarkApp] in [classes], checked against the root it declares.
 *
 * [failOn] is the severity a finding has to reach to stop a build, so a codebase that wants its
 * warnings fatal passes `WARN`. A diagram of each graph is written into [diagrams] where one is
 * given, which is the drawing the IDE renders.
 */
fun checkWiring(classes: List<File>, diagrams: File? = null, failOn: Severity = Severity.FAIL): Checked {
    val checked = apps(classes).map { app ->
        diagrams?.let { into ->
            into.mkdirs()
            File(into, "${nameOf(app)}.mmd").writeText(app.module.render())
        }
        nameOf(app) to app.module.findings(app.root)
    }

    val failed = checked.any { (_, findings) -> findings.any { it.severity >= failOn } }
    val said = checked.filter { (_, findings) -> findings.isNotEmpty() }
        .map { (name, findings) -> "$name\n\n${findings.report()}" }

    return Checked(said.joinToString("\n\n"), failed)
}

/**
 * The applications declared in [classes], found by their supertype.
 *
 * Loaded without initialising, so scanning a directory of unrelated classes runs none of their
 * static state; only a match is initialised, and initialising one runs the expression that
 * assembles its graph but none of the recipes in it.
 */
internal fun apps(classes: List<File>): List<LarkApp<*>> =
    classes.filter { it.isDirectory }
        .flatMap { root -> root.walkTopDown().filter { it.extension == "class" }.map { classNameOf(root, it) } }
        .sorted()
        .mapNotNull { name -> loaded(name) }
        .filter { LarkApp::class.java.isAssignableFrom(it) && it != LarkApp::class.java }
        .mapNotNull { declared(it) }

private fun classNameOf(root: File, classFile: File): String =
    classFile.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')

// A class that will not link is not an application, and a build tool that stopped on one would stop
// on every optional dependency a project left off its runtime classpath.
private fun loaded(name: String): Class<*>? =
    runCatching { Class.forName(name, false, LarkApp::class.java.classLoader) }.getOrNull()

/** An application is an `object`, and a class with a constructor taking nothing also answers. */
private fun declared(type: Class<*>): LarkApp<*>? =
    runCatching { type.getDeclaredField("INSTANCE").get(null) }
        .recoverCatching { type.getDeclaredConstructor().newInstance() }
        .getOrNull() as? LarkApp<*>

private fun nameOf(app: LarkApp<*>): String = app::class.simpleName ?: app::class.java.name

/**
 * Standard error as UTF-8 rather than whatever the platform defaults to.
 *
 * From JDK 19 `System.err` follows `stderr.encoding`, which is the native encoding when the stream is
 * redirected — so on a runner that is not UTF-8 the report's own bullet arrives as a question mark,
 * and `-Dfile.encoding` does not fix it.
 */
private fun said(): PrintStream = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)

/**
 * The check as a process, for a build to run against a project's own class output.
 *
 * `classes` is separated by the platform's path separator; `diagrams` is a directory or empty for
 * none; `failOn` is a [Severity] name.
 */
fun main(args: Array<String>) {
    val classes = args.getOrElse(0) { "" }.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)
    val diagrams = args.getOrElse(1) { "" }.takeIf { it.isNotBlank() }?.let(::File)
    val failOn = Severity.valueOf(args.getOrElse(2) { Severity.FAIL.name })

    val checked = checkWiring(classes, diagrams, failOn)
    if (checked.report.isNotBlank()) said().println(checked.report)
    exitProcess(if (checked.failed) 1 else 0)
}
