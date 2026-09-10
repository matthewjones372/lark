package io.github.matthewjones372.lark.app.liquibase

import io.github.matthewjones372.lark.app.render
import io.github.matthewjones372.lark.app.report
import io.github.matthewjones372.lark.app.single
import io.github.matthewjones372.lark.app.testApp
import io.github.matthewjones372.lark.app.validate
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

private const val CHANGELOG = "db/changelog.xml"

/** A count nobody could have taken before the changelog created the table. */
private class Orders(val rows: Int)

class MigrationsTest {

    private fun database(name: String): DataSource =
        JdbcDataSource().apply { setURL("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1") }

    private fun counting(source: DataSource): Int =
        source.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select count(*) from orders").use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    @Test
    fun `a changelog runs, and what it created is there to read`() {
        val source = database("applied")
        val app = single<DataSource> { source } +
            migrations(CHANGELOG) +
            single { db: DataSource, _: Migrated -> Orders(counting(db)) }

        testApp(app) { orders: Orders -> orders.rows } shouldBe 0
    }

    @Test
    fun `running it again applies nothing`() {
        val source = database("twice")
        val applied = AtomicInteger(-1)
        val app = single<DataSource> { source } +
            migrations(CHANGELOG) +
            single { done: Migrated -> applied.set(done.applied); Orders(0) }

        testApp(app) { _: Orders -> }
        val first = applied.get()
        testApp(app) { _: Orders -> }

        withClue("a changelog is a record of what has run, not a script that runs again") {
            first shouldBe 1
            applied.get() shouldBe 0
        }
    }

    @Test
    fun `the graph draws the reader after the migration`() {
        val app = single<DataSource> { database("drawn") } +
            migrations(CHANGELOG) +
            single { _: Migrated -> Orders(0) }

        withClue("an ordering nobody can see is an ordering nobody keeps") {
            app.render() shouldContain "Migrated --> Orders"
        }
        app.validate().getOrNull().shouldNotBeNull()
    }

    @Test
    fun `a graph with no DataSource is refused before anything connects`() {
        val app = migrations(CHANGELOG) + single { _: Migrated -> Orders(0) }

        val errors = app.validate().leftOrNull().shouldNotBeNull()

        errors.report() shouldContain "missing DataSource"
    }
}
