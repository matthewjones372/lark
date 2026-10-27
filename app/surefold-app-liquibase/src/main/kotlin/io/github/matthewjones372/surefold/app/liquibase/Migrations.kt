package io.github.matthewjones372.surefold.app.liquibase

import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.logInfo
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import javax.sql.DataSource

/** surefold-app's `Migrated` under this module's name, for code written against it. */
@Deprecated(
    "Migrated is surefold-app's, so a reader does not depend on which tool migrated.",
    ReplaceWith("Migrated", "io.github.matthewjones372.surefold.app.Migrated"),
)
typealias Migrated = io.github.matthewjones372.surefold.app.Migrated

/**
 * Runs [changelog] against the [DataSource] the graph provides, once, before anything that takes a
 * [io.github.matthewjones372.surefold.app.Migrated] is built.
 *
 * The connection is the application's, and is given back before the node answers: a migration that
 * held one for the life of the process would be a pool slot nothing ever uses again.
 */
fun migrations(
    changelog: String,
    contexts: Contexts = Contexts(),
    labels: LabelExpression = LabelExpression(),
): Module = single { source: DataSource ->
    io.github.matthewjones372.surefold.app.Migrated(migrate(source, changelog, contexts, labels))
}

/**
 * Runs [changelog], a classpath resource, against [source] now, and answers how many changesets it applied. For a
 * database outside a graph; [migrations] is this as a node.
 */
fun migrate(
    source: DataSource,
    changelog: String,
    contexts: Contexts = Contexts(),
    labels: LabelExpression = LabelExpression(),
): Int = source.connection.use { connection ->
    val database = DatabaseFactory.getInstance()
        .findCorrectDatabaseImplementation(JdbcConnection(connection))
    Liquibase(changelog, ClassLoaderResourceAccessor(), database).use { liquibase ->
        val unrun = liquibase.listUnrunChangeSets(contexts, labels).size
        liquibase.update(contexts, labels)
        logInfo("liquibase: $changelog, $unrun changeset(s) applied")
        unrun
    }
}
