package io.github.matthewjones372.surefold.app.liquibase

import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.app.render
import io.github.matthewjones372.surefold.app.report
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.app.validate
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldContain
import io.github.matthewjones372.surefold.junit.shouldNotBeNull
import io.github.matthewjones372.surefold.junit.withClue
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

private const val CHANGELOG = "db/changelog.sql"

/** A count nobody could have taken before the changelog created the table. */
private class Orders(val rows: Int)

private fun database(): DataSource = Postgres.empty()

private fun counting(source: DataSource): Int =
    source.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select count(*) from orders").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

object MigrationsSuite : Suite() {
    init {
        test("a changelog runs, and what it created is there to read") {
            val source = database()
            val app = single<DataSource> { source } +
                migrations(CHANGELOG) +
                single { db: DataSource, _: Migrated -> Orders(counting(db)) }

            testApp(app) { orders: Orders -> orders.rows } shouldBe 0
        }

        test("running it again applies nothing") {
            val source = database()
            val applied = AtomicInteger(-1)
            val app = single<DataSource> { source } +
                migrations(CHANGELOG) +
                single { done: Migrated -> applied.set(done.applied); Orders(0) }

            testApp(app) { _: Orders -> }
            val first = applied.get()
            testApp(app) { _: Orders -> }

            withClue("a changelog is a record of what has run, not a script that runs again") {
                (first shouldBe 1) and
                    (applied.get() shouldBe 0)
            }
        }

        test("the graph draws the reader after the migration") {
            val app = single<DataSource> { database() } +
                migrations(CHANGELOG) +
                single { _: Migrated -> Orders(0) }

            withClue("an ordering nobody can see is an ordering nobody keeps") {
                app.render() shouldContain "Migrated --> Orders"
            } and app.validate().getOrNull().shouldNotBeNull()
        }

        test("a graph with no DataSource is refused before anything connects") {
            val app = migrations(CHANGELOG) + single { _: Migrated -> Orders(0) }

            app.validate().leftOrNull().shouldNotBeNull { errors -> errors.report() shouldContain "missing DataSource" }
        }
    }
}
