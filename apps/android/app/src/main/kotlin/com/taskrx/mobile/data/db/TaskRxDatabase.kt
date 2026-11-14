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
    entities = [
        ReminderEntity::class,
        ReminderInstanceEntity::class,
        SyncQueueEntity::class,
        NoticeEntity::class,
        WatchedSourceEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class TaskRxDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    abstract fun reminderInstanceDao(): ReminderInstanceDao

    abstract fun syncQueueDao(): SyncQueueDao

    abstract fun noticeDao(): NoticeDao

    abstract fun watchedSourceDao(): WatchedSourceDao

    companion object {
        const val FILE_NAME = "taskrx.db"

        /** 1 to 2: a Reminder can be paused (`pauses`, a JSON array of windows). */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminders ADD COLUMN pauses TEXT NOT NULL DEFAULT '[]'")
                }
            }

        /** 2 to 3: one Occurrence can be moved (`overrides`, a JSON array of moves). */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminders ADD COLUMN overrides TEXT NOT NULL DEFAULT '[]'")
                }
            }

        /**
         * 3 to 4: notices and watched sources as data, and the rules that let a notice be
         * proposed against a Reminder (`notice_rules`, a JSON array).
         *
         * The two new tables are created empty. Nothing is backfilled: a person with no
         * watched source has nothing to keep, which is the feature's opt-in default.
         */
        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("ALTER TABLE reminders ADD COLUMN notice_rules TEXT NOT NULL DEFAULT '[]'")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `notices` (
                            `id` TEXT NOT NULL,
                            `user_id` TEXT NOT NULL,
                            `convex_id` TEXT,
                            `source_kind` TEXT NOT NULL,
                            `source_value` TEXT NOT NULL,
                            `title` TEXT NOT NULL,
                            `sentence` TEXT,
                            `received_at` INTEGER NOT NULL,
                            `external_id` TEXT,
                            `resolution` TEXT NOT NULL,
                            `resolved_at` INTEGER,
                            `created_at` INTEGER NOT NULL,
                            `updated_at` INTEGER NOT NULL,
                            `sync_status` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_notices_user_id_received_at` ON `notices` (`user_id`, `received_at`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_notices_user_id_external_id` ON `notices` (`user_id`, `external_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_notices_convex_id` ON `notices` (`convex_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_notices_sync_status` ON `notices` (`sync_status`)")
                    db.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `watch_sources` (
                            `id` TEXT NOT NULL,
                            `user_id` TEXT NOT NULL,
                            `convex_id` TEXT,
                            `kind` TEXT NOT NULL,
                            `value` TEXT NOT NULL,
                            `label` TEXT NOT NULL,
                            `created_at` INTEGER NOT NULL,
                            `updated_at` INTEGER NOT NULL,
                            `sync_status` TEXT NOT NULL,
                            PRIMARY KEY(`id`)
                        )
                        """.trimIndent(),
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_sources_user_id` ON `watch_sources` (`user_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_sources_convex_id` ON `watch_sources` (`convex_id`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_watch_sources_sync_status` ON `watch_sources` (`sync_status`)")
                }
            }

        fun open(context: Context): TaskRxDatabase =
            Room.databaseBuilder(context.applicationContext, TaskRxDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
