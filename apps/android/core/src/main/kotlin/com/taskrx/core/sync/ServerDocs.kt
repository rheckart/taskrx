package com.taskrx.core.sync

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.Notice
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.pausesFromJson
import com.taskrx.core.domain.noticeRulesFromJson
import com.taskrx.core.domain.overridesFromJson
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.WatchedSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/*
 * The documents `reminders.list`, `reminderInstances.list`, `notices.list` and `watchSources.list`
 * return, as a Convex client delivers them (ignoreUnknownKeys, special floats allowed), and the
 * local rows they become. Shared by the phone's pull and the desktop Companion (ADR 0017), and
 * pinned by the `server-docs` golden fixtures.
 *
 * Two things differ from the domain types and are handled HERE, once:
 *
 *  - `_id` and `_creationTime` are system fields. `_id` is the server's handle for the row and
 *    belongs in `convex_id`; the device's own primary key is `localId`.
 *  - Every number is a float64, and a whole one is spelled `1700000000000.0` (or `1.7E12`) as
 *    often as `1700000000000`. A `Long` field would reject the first spellings, so every
 *    number is read as a Double and narrowed explicitly in the mapping below.
 */

/** A pulled Reminder, with the server's id for it and the RxNorm id the domain type does not carry. */
data class PulledReminder(
    val reminder: Reminder,
    val convexId: String,
    val rxcui: String?,
)

/** A pulled Instance, its `reminderId` already the parent's LOCAL id. */
data class PulledReminderInstance(
    val instance: ReminderInstance,
    val convexId: String,
)

data class PulledNotice(
    val notice: Notice,
    val convexId: String,
)

data class PulledWatchedSource(
    val source: WatchedSource,
    val convexId: String,
)

@Serializable
data class ServerReminder(
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
    /** Absent on a document written before pausing existed. */
    val pauses: JsonElement? = null,
    /** Absent on a document written before occurrences could be moved. */
    val overrides: JsonElement? = null,
    /** Absent on a document written before notices could be proposed against it. */
    val noticeRules: JsonElement? = null,
)

@Serializable
data class ServerInstance(
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

@Serializable
data class ServerNotice(
    @SerialName("_id") val id: String,
    val localId: String,
    val userId: String,
    val sourceKind: String,
    val sourceValue: String,
    val title: String,
    val sentence: String? = null,
    val receivedAt: Double,
    val externalId: String? = null,
    val resolution: String,
    val resolvedAt: Double? = null,
    val createdAt: Double,
    val updatedAt: Double,
)

@Serializable
data class ServerWatchedSource(
    @SerialName("_id") val id: String,
    val localId: String,
    val userId: String,
    val kind: String,
    val value: String,
    val label: String,
    val createdAt: Double,
    val updatedAt: Double,
)

object ServerDocs {
    private val json = Json { ignoreUnknownKeys = true }

    fun reminders(list: JsonElement): List<ServerReminder> = json.decodeFromJsonElement(list)

    fun instances(list: JsonElement): List<ServerInstance> = json.decodeFromJsonElement(list)

    fun notices(list: JsonElement): List<ServerNotice> = json.decodeFromJsonElement(list)

    fun watchSources(list: JsonElement): List<ServerWatchedSource> = json.decodeFromJsonElement(list)

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
                    pauses = pausesFromJson(doc.pauses),
                    overrides = overridesFromJson(doc.overrides),
                    noticeRules = noticeRulesFromJson(doc.noticeRules),
                ),
            convexId = doc.id,
            rxcui = doc.rxcui,
        )
    }

    /**
     * Each server reminder id to the reminder's local id, for a reader that holds the documents
     * rather than a table (the desktop). An instance names its parent by the server id; the rows
     * the rules read join on the local one. The phone looks the same thing up in `reminders`.
     */
    fun parentLocalIds(reminders: List<ServerReminder>): Map<String, String> = reminders.associate { it.id to it.localId }

    /** An instance whose parent is in [parents] ([parentLocalIds]), or null: its parent was not pulled, or [toPulled] refused it. */
    fun toPulled(doc: ServerInstance, parents: Map<String, String>): PulledReminderInstance? = parents[doc.reminderId]?.let { toPulled(doc, it) }

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

    /** The row to write locally, or null if the document names a source kind or resolution this build does not know. */
    fun toPulled(doc: ServerNotice): PulledNotice? {
        val kind = SourceKind.fromWire(doc.sourceKind) ?: return null
        val resolution = NoticeResolution.fromWire(doc.resolution) ?: return null
        return PulledNotice(
            notice =
                Notice(
                    id = doc.localId,
                    userId = doc.userId,
                    sourceKind = kind,
                    sourceValue = doc.sourceValue,
                    title = doc.title,
                    sentence = doc.sentence,
                    receivedAt = doc.receivedAt.toLong(),
                    externalId = doc.externalId,
                    resolution = resolution,
                    resolvedAt = doc.resolvedAt?.toLong(),
                    createdAt = doc.createdAt.toLong(),
                    updatedAt = doc.updatedAt.toLong(),
                ),
            convexId = doc.id,
        )
    }

    fun toPulled(doc: ServerWatchedSource): PulledWatchedSource? {
        val kind = SourceKind.fromWire(doc.kind) ?: return null
        return PulledWatchedSource(
            source =
                WatchedSource(
                    id = doc.localId,
                    userId = doc.userId,
                    kind = kind,
                    value = doc.value,
                    label = doc.label,
                    createdAt = doc.createdAt.toLong(),
                    updatedAt = doc.updatedAt.toLong(),
                ),
            convexId = doc.id,
        )
    }
}
