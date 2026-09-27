package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.lark.actor.remote.Node
import io.github.matthewjones372.lark.cluster.Discovery
import io.github.matthewjones372.lark.logInfo
import kotlin.time.Duration.Companion.minutes

/** The three nodes and the ports their cluster listens on, on this host. */
internal val nodes = listOf("n1" to 25521, "n2" to 25522, "n3" to 25523)

/**
 * `lark-bank` runs the three nodes in this JVM, with the journal in H2 in memory. `--jdbc URL` keeps the journal in
 * the Postgres or H2 database at URL instead, and `--node n2` runs only that node, for a process of its own: separate
 * processes share one journal, so it needs `--jdbc`. The nodes run until the JVM is stopped.
 */
fun main(args: Array<String>) {
    require(args.size % 2 == 0) { "usage: lark-bank [--node n1|n2|n3] [--jdbc URL]" }
    val options = args.toList().chunked(2).associate { it[0] to it[1] }
    val jdbc = options["--jdbc"]
    val only = options["--node"]
    require(only == null || jdbc != null) { "--node needs --jdbc: separate processes must share one journal" }
    val journal = JdbcJournal(if (jdbc == null) h2("bank") else database(jdbc))
    val seeds = nodes.map { (_, port) -> Node("", "127.0.0.1", port) }.let { Discovery { it } }
    val running = nodes.filter { (name) -> only == null || name == only }
        .map { (name, port) -> BankNode(name, port, seeds, journal) }
    Runtime.getRuntime().addShutdownHook(Thread { running.forEach(BankNode::close) })
    running.forEach { node ->
        node.members.await(1.minutes) { seen -> seen.up.any { it.node.name == node.name } }
        logInfo("${node.name} is up")
    }
}
