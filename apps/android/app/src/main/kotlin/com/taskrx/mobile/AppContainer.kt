package com.taskrx.mobile

import android.content.Context
import com.taskrx.mobile.adherence.AdherenceReader
import com.taskrx.mobile.data.Clock
import com.taskrx.mobile.data.IdGenerator
import com.taskrx.mobile.data.RandomUuid
import com.taskrx.mobile.data.ReminderInstancesRepository
import com.taskrx.mobile.data.RemindersRepository
import com.taskrx.mobile.data.SyncQueueRepository
import com.taskrx.mobile.data.SystemClock
import com.taskrx.mobile.data.db.TaskRxDatabase
import com.taskrx.mobile.notifications.NotificationsModule
import com.taskrx.mobile.refill.RefillReader
import com.taskrx.mobile.sweep.MissedSweepRunner
import com.taskrx.mobile.sweep.RoomSweepStore

/**
 * Manual dependency injection: one object that builds the graph once.
 *
 * Everything here is a singleton for the life of the process, and that matters for one
 * member: the [sweep] runner's lock is what stops two foregrounds minting one moment
 * twice, so every caller must reach the same instance.
 *
 * The database is a parameter so tests build the same graph over an in-memory Room.
 */
class AppContainer(
    context: Context,
    val database: TaskRxDatabase,
    clock: Clock = SystemClock,
    newId: IdGenerator = RandomUuid,
) {
    val syncQueue = SyncQueueRepository(database.syncQueueDao(), clock)
    val reminders = RemindersRepository(database, syncQueue, clock, newId)
    val instances = ReminderInstancesRepository(database, syncQueue, clock, newId)

    val sweep = MissedSweepRunner(RoomSweepStore(reminders, instances))
    val adherence = AdherenceReader(sweep, instances)
    val refills = RefillReader(reminders, newId)

    val notifications = NotificationsModule(context, reminders, clock)

    companion object {
        fun create(context: Context): AppContainer = AppContainer(context, TaskRxDatabase.open(context))
    }
}
