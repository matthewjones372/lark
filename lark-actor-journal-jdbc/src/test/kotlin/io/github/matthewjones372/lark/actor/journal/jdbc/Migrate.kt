package io.github.matthewjones372.lark.actor.journal.jdbc

import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import javax.sql.DataSource

/** The changelog the jar ships for [database], applied by Liquibase as a service would; answers how many changesets ran. */
internal fun DataSource.migrate(database: String): Int = connection.use { connection ->
    val liquibaseDatabase = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
    Liquibase("lark/journal/jdbc/$database.sql", ClassLoaderResourceAccessor(), liquibaseDatabase).use { liquibase ->
        liquibase.listUnrunChangeSets(null, null).size.also { liquibase.update() }
    }
}

/** This database with the changelog for [database] applied. */
internal fun DataSource.migrated(database: String): DataSource = also { migrate(database) }
