package app.squeeze.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [BatchEntity::class, BatchItemEntity::class, PresetEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class SqueezeDatabase : RoomDatabase() {
    abstract fun batchDao(): BatchDao
    abstract fun batchItemDao(): BatchItemDao
    abstract fun presetDao(): PresetDao

    companion object {
        /** M3: user presets. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `preset` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`paramsJson` TEXT NOT NULL, `exportJson` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** M8: photo compression — media kind on batches/items, image params on presets. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `batch` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'VIDEO'")
                db.execSQL("ALTER TABLE `batch_item` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'VIDEO'")
                db.execSQL("ALTER TABLE `preset` ADD COLUMN `imageParamsJson` TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
