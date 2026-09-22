package snd.komelia.db

import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.sqlite.SQLiteDataSource
import snd.komelia.db.migrations.AppMigrations
import snd.komelia.db.settings.ExposedImageReaderSettingsRepository
import snd.komelia.db.tables.ImageReaderSettingsTable
import snd.komelia.settings.model.PanelTransitionMode
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PanelTransitionModeMigrationTest {

    private val insertRow = """
        INSERT INTO ImageReaderSettings (
            book_id, reader_type, stretch_to_fit, paged_scale_type,
            paged_reading_direction, paged_page_layout, continuous_reading_direction,
            continuous_padding, continuous_page_spacing, crop_borders
        ) VALUES (?, 'PAGED', 1, 'SCREEN', 'LEFT_TO_RIGHT', 'SINGLE_PAGE',
                  'TOP_TO_BOTTOM', 0, 0, 0)
    """.trimIndent()

    @Test
    fun `V12 rows migrate to SNAPPY for default and book specific entries`() {
        val dir = Files.createTempDirectory("komelia-panel-transition-migration")
        val url = "jdbc:sqlite:${dir.resolve("komelia.sqlite")}"
        try {
            val datasource = SQLiteDataSource().apply { this.url = url }

            // Bring an existing database up to V12, the schema before panel_transition_mode existed.
            migrate(datasource, target = "12")

            // Seed a DEFAULT row and a book-specific row against the V12 schema.
            DriverManager.getConnection(url).use { connection ->
                connection.prepareStatement(insertRow).use { statement ->
                    statement.setString(1, "DEFAULT")
                    statement.executeUpdate()
                    statement.setString(1, "book-1")
                    statement.executeUpdate()
                }
            }

            // Apply the remaining migrations; V13 adds the panel_transition_mode column.
            migrate(datasource, target = null)

            assertEquals("SNAPPY", queryMode(url, "DEFAULT"))
            assertEquals("SNAPPY", queryMode(url, "book-1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unknown persisted panel transition mode fails explicitly instead of coercing`() {
        val dir = Files.createTempDirectory("komelia-panel-transition-migration")
        val url = "jdbc:sqlite:${dir.resolve("komelia.sqlite")}"
        try {
            val datasource = SQLiteDataSource().apply { this.url = url }
            migrate(datasource, target = null)

            DriverManager.getConnection(url).use { connection ->
                connection.prepareStatement(insertRow).use { statement ->
                    statement.setString(1, "DEFAULT")
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "UPDATE ImageReaderSettings SET panel_transition_mode = 'TURBO' WHERE book_id = 'DEFAULT'"
                ).use { it.executeUpdate() }
            }

            val repository = ExposedImageReaderSettingsRepository(Database.connect(datasource))
            val failure = assertFailsWith<IllegalStateException> {
                runBlocking { repository.get() }
            }
            // The exception must come from the enum mapping path, not from an unrelated failure.
            assertTrue(
                failure.message?.contains("TURBO") == true,
                "expected the unknown literal to surface in the mapping failure, got: ${failure.message}"
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `table maps panel transition mode as an enum by name`() {
        assertEquals(
            PanelTransitionMode.SNAPPY,
            ImageReaderSettingsTable.panelTransitionMode.defaultValueFun?.invoke()
        )

        val dir = Files.createTempDirectory("komelia-panel-transition-mapping")
        val url = "jdbc:sqlite:${dir.resolve("komelia.sqlite")}"
        try {
            val datasource = SQLiteDataSource().apply { this.url = url }
            migrate(datasource, target = null)
            DriverManager.getConnection(url).use { connection ->
                connection.prepareStatement(insertRow).use { statement ->
                    statement.setString(1, "DEFAULT")
                    statement.executeUpdate()
                }
            }

            transaction(Database.connect(datasource)) {
                ImageReaderSettingsTable.update({ ImageReaderSettingsTable.bookId eq "DEFAULT" }) {
                    it[panelTransitionMode] = PanelTransitionMode.CLASSIC
                }

                val mode = ImageReaderSettingsTable.selectAll()
                    .where { ImageReaderSettingsTable.bookId eq "DEFAULT" }
                    .single()[ImageReaderSettingsTable.panelTransitionMode]
                assertEquals(PanelTransitionMode.CLASSIC, mode)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun migrate(datasource: SQLiteDataSource, target: String?) {
        val provider = AppMigrations()
        val configuration = Flyway.configure()
            .dataSource(datasource)
            .resourceProvider(provider)
            .javaMigrationClassProvider(provider)
        if (target != null) {
            configuration.target(target)
        }
        Flyway(configuration).migrate()
    }

    private fun queryMode(url: String, bookId: String): String {
        DriverManager.getConnection(url).use { connection ->
            connection.prepareStatement(
                "SELECT panel_transition_mode FROM ImageReaderSettings WHERE book_id = ?"
            ).use { statement ->
                statement.setString(1, bookId)
                statement.executeQuery().use { resultSet ->
                    check(resultSet.next()) { "no ImageReaderSettings row for $bookId" }
                    return resultSet.getString(1)
                }
            }
        }
    }
}

private fun Path.deleteRecursively() {
    Files.walk(this)
        .sorted(Comparator.reverseOrder())
        .forEach { Files.deleteIfExists(it) }
}
