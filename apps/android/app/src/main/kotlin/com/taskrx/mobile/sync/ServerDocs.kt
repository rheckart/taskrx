package com.taskrx.mobile.sync

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.sync.scheduleFromServer
import com.taskrx.mobile.data.PulledReminder
import com.taskrx.mobile.data.PulledReminderInstance
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/*
 * The documents `reminders.list` and `reminderInstances.list` return, as the Convex client
 * library delivers them (`jsonApi` in convex-mobile: ignoreUnknownKeys, special floats allowed).
 *
 * Two things differ from the domain types and are handled HERE, once:
 *
 *  - `_id` and `_creationTime` are system fields. `_id` is the server's handle for the row and
 *    belongs in `convex_id`; the device's own primary key is `localId`.
 *  - Every number is a float64, and a whole one is spelled `1700000000000.0` (or `1.7E12`) as
 *    often as `1700000000000`. A `Long` field would reject the first spellings, so every
 *    number is read as a Double and narrowed explicitly in the mapping below.
 */

@Serializable
internal data class ServerReminder(
    @SerialName("_id") val id: String,
    @SerialName("_creationTime") val creationTime: Double = 0.0,
    val localId: String,
    val userId: String,
    val kind: String,
    val name: String,
    val rxcui: String? = null,
    val dosage: String? = null,
    val instructions: String? = null,
    val schedule: JsonElement,
    val priority: String,
    val tags: List<String> = emptyList(),
    val pillsRemaining: Double? = null,
    val pillsPerDose: Double? = null,
    val refillReminderDays: Double? = null,
    val caregiverIds: List<String> = emptyList(),
    val active: Boolean,
    val createdAt: Double,
    val updatedAt: Double,
    val deletedAt: Double? = null,
)

@Serializable
internal data class ServerInstance(
    @SerialName("_id") val id: String,
    @SerialName("_creationTime") val creationTime: Double = 0.0,
    val localId: String,
    val userId: String,
    /** The PARENT's server id (`Id<"reminders">`), not its local UUID. */
    val reminderId: String,
    val reminderKind: String,
    val scheduledTime: Double,
    val status: String,
    val completedAt: Double? = null,
    val snoozedUntil: Double? = null,
    val snoozeCount: Double = 0.0,
    val notes: String? = null,
    val createdAt: Double,
    val updatedAt: Double,
)

internal object ServerDocs {
    private val json = Json { ignoreUnknownKeys = true }

    fun reminders(list: JsonElement): List<ServerReminder> = json.decodeFromJsonElement(list)

    fun instances(list: JsonElement): List<ServerInstance> = json.decodeFromJsonElement(list)

    /**
     * The row to write locally, or null if the document names a Kind this build does not know
     * (written by a newer app): skipped rather than guessed at.
     *
     * A schedule that cannot be read becomes `none`, i.e. the Backlog, where it can be repaired.
     */
    fun toPulled(doc: ServerReminder): PulledReminder? {
        val kind = ReminderKind.fromWire(doc.kind) ?: return null
        return PulledReminder(
            reminder =
                Reminder(
                    // The device UUID is the primary key everything local joins on.
                    id = doc.localId,
                    userId = doc.userId,
                    kind = kind,
                    name = doc.name,
                    dosage = doc.dosage,
                    instructions = doc.instructions,
                    schedule = scheduleFromServer(doc.schedule) ?: NoneTimes,
                    priority = Priority.fromWire(doc.priority) ?: Priority.Normal,
                    tags = doc.tags,
                    pillsRemaining = doc.pillsRemaining,
                    pillsPerDose = doc.pillsPerDose,
                    refillReminderDays = doc.refillReminderDays?.toInt(),
                    caregiverIds = doc.caregiverIds,
                    active = doc.active,
                    createdAt = doc.createdAt.toLong(),
                    updatedAt = doc.updatedAt.toLong(),
                ),
            convexId = doc.id,
            rxcui = doc.rxcui,
        )
    }

    /** As [toPulled]; [localReminderId] is the parent's LOCAL id, which the caller resolved. */
    fun toPulled(doc: ServerInstance, localReminderId: String): PulledReminderInstance? {
        val kind = ReminderKind.fromWire(doc.reminderKind) ?: return null
        val status = InstanceStatus.fromWire(doc.status) ?: return null
        return PulledReminderInstance(
            instance =
                ReminderInstance(
                    id = doc.localId,
                    // Never `doc.reminderId`: that is a server id, and the column is a foreign key
                    // to the local reminders table.
                    reminderId = localReminderId,
                    userId = doc.userId,
                    reminderKind = kind,
                    scheduledTime = doc.scheduledTime.toLong(),
                    status = status,
                    completedAt = doc.completedAt?.toLong(),
                    snoozedUntil = doc.snoozedUntil?.toLong(),
                    snoozeCount = doc.snoozeCount.toInt(),
                    notes = doc.notes,
                    createdAt = doc.createdAt.toLong(),
                    updatedAt = doc.updatedAt.toLong(),
                ),
            convexId = doc.id,
        )
    }
}
