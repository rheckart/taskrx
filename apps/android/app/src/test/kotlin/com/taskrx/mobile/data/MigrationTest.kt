package com.taskrx.mobile.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.domain.OccurrenceOverride
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
 * Rebuilds `reminders` without [column] and steps the version back to [version]. SQLite's
 * `DROP COLUMN` is newer than the one Robolectric ships, so the table is made again from its
 * own DDL minus that column, with its indices, and the rows copied across.
 */
private fun SupportSQLiteDatabase.dropColumn(column: String, version: Int) {
    fun sqlOf(type: String, name: String) =
        query("SELECT sql FROM sqlite_master WHERE type = '$type' AND name = '$name'").use { it.moveToFirst(); it.getString(0) }
    val indices =
        query("SELECT sql FROM sqlite_master WHERE type = 'index' AND tbl_name = 'reminders' AND sql IS NOT NULL")
            .use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList() }
    // The DDL may quote the table and its columns with backticks or double quotes, and the
    // column's default may or may not be spelled in it.
    val created = sqlOf("table", "reminders")
    val older =
        created
            .replace(Regex(""",\s*[`"]$column[`"][^,)]*"""), "")
            .replace(Regex("""(?i)^CREATE TABLE\s+[`"]?reminders[`"]?"""), "CREATE TABLE reminders_older")
    check(older != created) { "could not take $column out of: $created" }
    check(Regex("""[`"]$column[`"]""").find(older) == null) { "could not take $column out of: $older" }
    val columns =
        query("PRAGMA table_info(reminders)")
            .use { cursor -> generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.toList() }
            .filter { it != column }
            .joinToString(", ") { "`$it`" }

    execSQL("PRAGMA foreign_keys = OFF")
    execSQL(older)
    execSQL("INSERT INTO reminders_older ($columns) SELECT $columns FROM reminders")
    execSQL("DROP TABLE reminders")
    execSQL("ALTER TABLE reminders_older RENAME TO reminders")
    indices.forEach { execSQL(it) }
    execSQL("PRAGMA foreign_keys = ON")
    this.version = version
}

/**
 * The schema upgrades. The phone this runs on holds real doses and unsynced changes, so every
 * upgrade must keep every row, and a destructive fallback is not an option.
 *
 * No older schema was ever exported, so an old file is made by taking the current one and
 * removing the columns a later version added, which leaves exactly what the migration has to
 * cope with.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun open(name: String) =
        Room.databaseBuilder(context, TaskRxDatabase::class.java, name)
            .allowMainThreadQueries()
            .addMigrations(TaskRxDatabase.MIGRATION_1_2, TaskRxDatabase.MIGRATION_2_3)
            .build()

    @Test
    fun `upgrading from 1 keeps every Reminder and starts each one unpaused and unmoved`() {
        val name = "migration-1-3.db"
        context.deleteDatabase(name)

        val before = open(name)
        runBlocking {
            val repo = RemindersRepository(before, SyncQueueRepository(before.syncQueueDao()))
            repo.create(CreateReminderInput(id = "m1", userId = USER, kind = ReminderKind.Medication, name = "Metformin", schedule = noneTimes()))
            repo.create(CreateReminderInput(id = "t1", userId = USER, kind = ReminderKind.Task, name = "Call", schedule = noneTimes()))
        }
        before.openHelper.writableDatabase.dropColumn("overrides", version = 2)
        before.openHelper.writableDatabase.dropColumn("pauses", version = 1)
        before.close()

        val after = open(name)
        try {
            runBlocking {
                val repo = RemindersRepository(after, SyncQueueRepository(after.syncQueueDao()))
                assertEquals(setOf("Metformin", "Call"), repo.list(USER).map { it.name }.toSet())
                assertEquals(emptyList(), repo.get("m1")!!.pauses)
                assertEquals(emptyList(), repo.get("t1")!!.overrides)
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

    @Test
    fun `upgrading from 2 keeps every Reminder and starts each one unmoved`() {
        val name = "migration-2-3.db"
        context.deleteDatabase(name)

        val before = open(name)
        runBlocking {
            val repo = RemindersRepository(before, SyncQueueRepository(before.syncQueueDao()))
            repo.create(CreateReminderInput(id = "m1", userId = USER, kind = ReminderKind.Medication, name = "Metformin", schedule = noneTimes()))
            repo.create(CreateReminderInput(id = "t1", userId = USER, kind = ReminderKind.Task, name = "Call", schedule = noneTimes()))
            repo.pause("m1", from = 5, until = 9)
        }
        before.openHelper.writableDatabase.dropColumn("overrides", version = 2)
        before.close()

        val after = open(name)
        try {
            runBlocking {
                val repo = RemindersRepository(after, SyncQueueRepository(after.syncQueueDao()))
                assertEquals(setOf("Metformin", "Call"), repo.list(USER).map { it.name }.toSet())
                // The pause written before the upgrade survives it.
                assertEquals(listOf(PauseWindow(5, 9)), repo.get("m1")!!.pauses)
                assertEquals(emptyList(), repo.get("t1")!!.overrides)

                // And a move written after it round-trips through the new column.
                val moved = repo.update("t1", UpdateReminderPatch(overrides = listOf(OccurrenceOverride(100, 200))))
                assertEquals(listOf(OccurrenceOverride(100, 200)), moved.overrides)
                assertEquals(listOf(OccurrenceOverride(100, 200)), repo.get("t1")!!.overrides)
            }
        } finally {
            after.close()
            context.deleteDatabase(name)
        }
    }
}
