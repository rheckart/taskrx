package com.taskrx.mobile

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.noneTimes
import com.taskrx.mobile.data.CreateReminderInput
import com.taskrx.mobile.data.db.TaskRxDatabase
import com.taskrx.mobile.ui.search.DrugSearchService
import com.taskrx.mobile.ui.search.OfflineDrugSearch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

const val USER = "user-1"
const val HOUR = 3_600_000L
const val DAY = 24 * HOUR

/**
 * An in-memory Room database with the real [AppContainer] over it, a clock the test
 * moves by hand, and ids that count up. Real storage rather than fakes, so the
 * assertions read what was stored.
 */
class Fixture(var now: Long, drugSearch: DrugSearchService = OfflineDrugSearch) {
    private var counter = 0

    val database: TaskRxDatabase =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), TaskRxDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    val container = AppContainer(ApplicationProvider.getApplicationContext(), database, clock = { now }, newId = { "id-${++counter}" }, drugSearch = drugSearch)

    fun close() = database.close()

    /** Creates a Reminder as of [createdAt] (the clock is put back afterwards). */
    suspend fun reminder(
        id: String,
        kind: ReminderKind = ReminderKind.Medication,
        schedule: ScheduleTimes = noneTimes(),
        createdAt: Long = now,
        name: String = "Reminder $id",
        configure: (CreateReminderInput) -> CreateReminderInput = { it },
    ): Reminder {
        val present = now
        now = createdAt
        try {
            return container.reminders.create(
                configure(CreateReminderInput(id = id, userId = USER, kind = kind, name = name, schedule = schedule)),
            )
        } finally {
            now = present
        }
    }

    suspend fun queuePayloads(): List<JsonObject> =
        container.syncQueue.pending().map { Json.parseToJsonElement(it.payload).jsonObject }
}

/** Runs [block] against a fresh [Fixture] and always closes it. */
fun withFixture(now: Long, block: suspend Fixture.() -> Unit) {
    val fixture = Fixture(now)
    try {
        runBlocking { fixture.block() }
    } finally {
        fixture.close()
    }
}
