package io.github.matthewjones372.lark.app.liquibase

import io.github.matthewjones372.lark.app.Module
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.logInfo
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import javax.sql.DataSource

/**
 * The database is at the changelog's latest version.
 *
 * A node that reads the database takes one of these, which makes "after the migrations" an edge in
 * the graph rather than a line in a comment: the reader cannot be built before the writer has run,
 * and `render()` draws the ordering.
 */
class Migrated internal constructor(val applied: Int)

/**
 * Runs [changelog] against the [DataSource] the graph provides, once, before anything that takes a
 * [Migrated] is built.
 *
 * The connection is the application's, and is given back before the node answers: a migration that
 * held one for the life of the process would be a pool slot nothing ever uses again.
 */
fun migrations(
    changelog: String,
    contexts: Contexts = Contexts(),
    labels: LabelExpression = LabelExpression(),
): Module = single { source: DataSource ->
    source.connection.use { connection ->
        val database = DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(JdbcConnection(connection))
        Liquibase(changelog, ClassLoaderResourceAccessor(), database).use { liquibase ->
            val unrun = liquibase.listUnrunChangeSets(contexts, labels).size
            liquibase.update(contexts, labels)
            logInfo("liquibase: $changelog, $unrun changeset(s) applied")
            Migrated(unrun)
        }
    }
}
