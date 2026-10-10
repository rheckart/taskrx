package com.taskrx.mobile.data

import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.parseScheduleTimes
import com.taskrx.core.domain.serializeScheduleTimes
import com.taskrx.mobile.data.db.ReminderEntity
import com.taskrx.mobile.data.db.ReminderInstanceEntity
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/** The instant "now", Unix ms. A parameter of every repository so tests can state it. */
typealias Clock = () -> Long

/** Mints local primary keys. Ids are made on-device so a row can be written and referenced offline. */
typealias IdGenerator = () -> String

val SystemClock: Clock = { System.currentTimeMillis() }

val RandomUuid: IdGenerator = { UUID.randomUUID().toString() }

/**
 * Column codecs. Pure translation between a stored text column and a domain value;
 * no rule about schedules, priorities or statuses is decided here - :core owns the
 * schedule JSON, and the enums own their wire words.
 */
internal object Codecs {
    /** The `schedule` column: the JSON text :core already writes. */
    fun scheduleToColumn(schedule: ScheduleTimes): String = serializeScheduleTimes(schedule).toString()

    /**
     * Reads the `schedule` column back. An unreadable blob degrades to `none` rather
     * than throwing, as the Expo repo does: a crash on the list screen is not
     * recoverable, and the Backlog is exactly where a corrupt row can be repaired
     * ("give it a time"), whereas an empty daily schedule would claim to be a
     * schedule while producing nothing.
     */
    fun scheduleFromColumn(text: String): ScheduleTimes =
        try {
            parseScheduleTimes(Json.parseToJsonElement(text)) ?: NoneTimes
        } catch (_: IllegalArgumentException) {
            // kotlinx.serialization's SerializationException extends IllegalArgumentException.
            NoneTimes
        }

    fun stringsToColumn(values: List<String>): String =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

    fun stringsFromColumn(text: String): List<String> =
        try {
            (Json.parseToJsonElement(text) as? JsonArray).orEmpty().mapNotNull {
                (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content
            }
        } catch (_: IllegalArgumentException) {
            emptyList()
        }

    fun toReminder(row: ReminderEntity): Reminder =
        Reminder(
            id = row.id,
            userId = row.userId,
            kind = ReminderKind.fromWire(row.kind) ?: ReminderKind.Task,
            name = row.name,
            dosage = row.dosage,
            instructions = row.instructions,
            schedule = scheduleFromColumn(row.schedule),
            priority = Priority.fromWire(row.priority) ?: Priority.Normal,
            tags = stringsFromColumn(row.tags),
            pillsRemaining = row.pillsRemaining,
            pillsPerDose = row.pillsPerDose,
            refillReminderDays = row.refillReminderDays,
            caregiverIds = stringsFromColumn(row.caregiverIds),
            active = row.active,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )

    fun toInstance(row: ReminderInstanceEntity): ReminderInstance =
        ReminderInstance(
            id = row.id,
            reminderId = row.reminderId,
            userId = row.userId,
            reminderKind = ReminderKind.fromWire(row.reminderKind) ?: ReminderKind.Task,
            scheduledTime = row.scheduledTime,
            status = InstanceStatus.fromWire(row.status) ?: InstanceStatus.Pending,
            completedAt = row.completedAt,
            snoozedUntil = row.snoozedUntil,
            snoozeCount = row.snoozeCount,
            notes = row.notes,
            createdAt = row.createdAt,
            updatedAt = row.updatedAt,
        )
}
