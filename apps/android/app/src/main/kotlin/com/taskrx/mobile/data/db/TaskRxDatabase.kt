package com.taskrx.mobile.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The on-device source of truth (data-flow rule 1).
 *
 * Version 1 is a fresh schema: there is no released build and no user data, so
 * no migration from the Expo database exists or is wanted (spec phase 3).
 */
@Database(
    entities = [ReminderEntity::class, ReminderInstanceEntity::class, SyncQueueEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TaskRxDatabase : RoomDatabase() {
    abstract fun reminderDao(): ReminderDao

    abstract fun reminderInstanceDao(): ReminderInstanceDao

    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        const val FILE_NAME = "taskrx.db"

        fun open(context: Context): TaskRxDatabase =
            Room.databaseBuilder(context.applicationContext, TaskRxDatabase::class.java, FILE_NAME).build()
    }
}
