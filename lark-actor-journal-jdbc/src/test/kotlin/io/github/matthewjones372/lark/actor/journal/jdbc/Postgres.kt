package io.github.matthewjones372.lark.actor.journal.jdbc

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * One real Postgres for the test JVM (spec 0078), started on first use and stopped when the JVM exits, and a fresh
 * database in it for each test with `postgres.sql` applied.
 */
internal object Postgres {
    private val server: EmbeddedPostgres by lazy {
        EmbeddedPostgres.builder().start().also { started ->
            Runtime.getRuntime().addShutdownHook(Thread(started::close))
        }
    }
    private val made = AtomicInteger()

    fun fresh(): DataSource = empty().migrated("postgres")

    /** A fresh database with nothing in it. */
    fun empty(): DataSource {
        val name = "lark_${made.incrementAndGet()}"
        server.postgresDatabase.connection.use { admin ->
            admin.createStatement().use { statement -> statement.execute("create database $name") }
        }
        return server.getDatabase("postgres", name)
    }
}
