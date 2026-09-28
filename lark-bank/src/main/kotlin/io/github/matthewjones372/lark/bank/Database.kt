package io.github.matthewjones372.lark.bank

import io.github.matthewjones372.lark.app.liquibase.migrate
import org.h2.jdbcx.JdbcDataSource
import org.postgresql.ds.PGSimpleDataSource
import javax.sql.DataSource

/** An H2 database in this JVM named [name], with the journal's tables, for as long as the JVM runs. */
internal fun h2(name: String): DataSource = database("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1")

/**
 * The database at [url], Postgres or H2, with the journal's tables. The library leaves its tables to a service's
 * migrations; this application has no others, so it runs the changelog the jar ships, which Liquibase applies once.
 */
internal fun database(url: String): DataSource {
    val postgres = url.startsWith("jdbc:postgresql:")
    val source = if (postgres) PGSimpleDataSource().apply { setURL(url) } else JdbcDataSource().apply { setURL(url) }
    migrate(source, if (postgres) POSTGRES else H2)
    return source
}

private const val H2 = "lark/journal/jdbc/h2.sql"
private const val POSTGRES = "lark/journal/jdbc/postgres.sql"
