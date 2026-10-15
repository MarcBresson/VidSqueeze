package app.squeeze.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        SqueezeDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate1To2KeepsQueueDataAndAddsPresets() {
        helper.createDatabase(DB, 1).apply {
            execSQL(
                "INSERT INTO batch (id, source, presetId, presetName, presetSnapshotJson, exportMode, createdAt, status) " +
                    "VALUES (1, 'SHARE', 'builtin.share', 'Share', '{}', 'ASK_WHEN_DONE', 1000, 'QUEUED')",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 2, true, SqueezeDatabase.MIGRATION_1_2)

        db.query("SELECT presetName FROM batch WHERE id = 1").use {
            it.moveToFirst()
            assertEquals("Share", it.getString(0))
        }
        db.execSQL("INSERT INTO preset VALUES ('user.1', 'P', '{}', '{}', 1, 1)")
        db.query("SELECT COUNT(*) FROM preset").use {
            it.moveToFirst()
            assertEquals(1, it.getInt(0))
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
