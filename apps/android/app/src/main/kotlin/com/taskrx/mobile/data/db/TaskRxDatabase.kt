package com.taskrx.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The on-device source of truth (data-flow rule 1).
 *
 * Version 1 was a fresh schema, with no migration from the Expo database (spec phase 3).
 * Since then the app holds real doses, so every later version is a [Migration] and never a
 * destructive fallback: that would drop whatever has not synced yet.
 */
@Database(
    entities = [ReminderEntity::class, ReminderInstanceEntity::class, SyncQueueEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class TaskRxDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    abstract fun reminderInstanceDao(): ReminderInstanceDao

    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        const val FILE_NAME = "taskrx.db"

        /** 1 to 2: a Reminder can be paused (`pauses`, a JSON array of windows). */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminders ADD COLUMN pauses TEXT NOT NULL DEFAULT '[]'")
                }
            }

        fun open(context: Context): TaskRxDatabase =
            Room.databaseBuilder(context.applicationContext, TaskRxDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
