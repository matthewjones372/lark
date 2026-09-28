package io.github.matthewjones372.lark.actor.journal.jdbc

import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import javax.sql.DataSource

/** The changelog the jar ships, applied by Liquibase as a service would; answers how many changesets ran. */
fun DataSource.migrate(): Int = connection.use { connection ->
    val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
    Liquibase("lark/journal/jdbc/postgres.sql", ClassLoaderResourceAccessor(), database).use { liquibase ->
        liquibase.listUnrunChangeSets(null, null).size.also { liquibase.update() }
    }
}

/** This database with the changelog applied. */
fun DataSource.migrated(): DataSource = also { migrate() }
