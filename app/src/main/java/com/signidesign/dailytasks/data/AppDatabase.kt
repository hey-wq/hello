package com.signidesign.dailytasks.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TaskEntity::class, DayNoteEntity::class, DeletedTaskEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun dayNoteDao(): DayNoteDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        // v2: manual ordering. Seed sortOrder from id so existing rows keep
        // their creation order.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE tasks ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL("UPDATE tasks SET sortOrder = id")
            }
        }

        // v3: sync support — per-task uuid + updatedAt, note timestamps, and
        // deletion tombstones.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN uuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tasks ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE tasks SET uuid = lower(hex(randomblob(16)))")
                db.execSQL("UPDATE tasks SET updatedAt = createdAt")
                db.execSQL(
                    "ALTER TABLE day_notes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `deleted_tasks` (
                       `uuid` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL,
                       PRIMARY KEY(`uuid`))"""
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "daily-tasks.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
    }
}
