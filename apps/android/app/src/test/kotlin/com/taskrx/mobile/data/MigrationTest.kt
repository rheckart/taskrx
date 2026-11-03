package com.taskrx.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.noneTimes
import com.taskrx.mobile.USER
import com.taskrx.mobile.data.db.TaskRxDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rebuilds `reminders` without `pauses` and steps the version back to 1. SQLite's `DROP COLUMN`
 * is newer than the one Robolectric ships, so the table is made again from its own DDL minus
 * that column, with its indices, and the rows copied across.
 */
private fun SupportSQLiteDatabase.toVersion1() {
    fun sqlOf(type: String, name: String) =
        query("SELECT sql FROM sqlite_master WHERE type = '$type' AND name = '$name'").use { it.moveToFirst(); it.getString(0) }
    val indices =
        query("SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'reminders' AND sql IS NOT NULL")
            .use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList() }
    val v1 = sqlOf("table", "reminders").replace(", `pauses` TEXT NOT NULL DEFAULT '[]'", "")
    check("pauses" !in v1) { "could not take the column out of: $v1" }
    val columns =
        query("PRAGMA table_info(reminders)")
            .use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toList() }
            .filter { it != "pauses" }
            .joinToString(", ") { "`$it`" }

    execSQL("PRAGMA foreign_keys = OFF")
    execSQL(v1.replace("`reminders`", "`reminders_v1`"))
    execSQL("INSERT INTO reminders_v1 ($columns) SELECT $columns FROM reminders")
    execSQL("DROP TABLE reminders")
    execSQL("ALTER TABLE reminders_v1 RENAME TO reminders")
    indices.forEach { execSQL(it) }
    execSQL("PRAGMA foreign_keys = ON")
    version = 1
}

/**
 * Version 1 to 2 adds `reminders.pauses`. The phone this runs on holds real doses and
 * unsynced changes, so the upgrade must keep every row, and a destructive fallback is not
 * an option.
 *
 * No version-1 schema was ever exported, so the old file is made by taking a version-2 one
 * and removing the column and stepping the version back, which leaves what the migration has
 * to cope with.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun open(name: String) =
        Room.databaseBuilder(context, TaskRxDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(TaskRxDatabase.MIGRATION_1_2)
            .build()

    @Test
    fun `upgrading keeps every Reminder and starts each one unpaused`() {
        val name = "migration-test.db"
        context.deleteDatabase(name)

        val before = open(name)
        runBlocking {
            val repo = RemindersRepository(before, SyncQueueRepository(before.syncQueueDao()))
            repo.create(CreateReminderInput(id = "m1", userId = USER, kind = ReminderKind.Medication, name = "Metformin", schedule = noneTimes()))
            repo.create(CreateReminderInput(id = "t1", userId = USER, kind = ReminderKind.Task, name = "Call", schedule = noneTimes()))
        }
        before.openHelper.writableDatabase.toVersion1()
        before.close()

        val after = open(name)
        try {
            runBlocking {
                val repo = RemindersRepository(after, SyncQueueRepository(after.syncQueueDao()))
                assertEquals(setOf("Metformin", "Call"), repo.list(USER).map { it.name }.toSet())
                assertEquals(emptyList(), repo.get("m1")!!.pauses)
                assertEquals(1, after.syncQueueDao().let { SyncQueueRepository(it).pending() }.count { it.entityId == "m1" })

                val paused = repo.pause("m1", from = 5, until = 9)
                assertEquals(listOf(PauseWindow(5, 9)), paused.pauses)
                assertNotNull(repo.get("t1"))
            }
        } finally {
            after.close()
            context.deleteDatabase(name)
        }
    }
}
