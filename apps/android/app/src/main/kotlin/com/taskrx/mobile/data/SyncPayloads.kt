package com.taskrx.mobile.data

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.WeeklyTimes
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The JSON a `sync_queue` row carries, shaped for what the sync worker pushes into
 * `api.reminders.create/update/delete` and `api.reminderInstances.create/update`
 * (apps/mobile/src/sync/worker.ts `ReminderPayload` / `ReminderInstancePayload`).
 *
 * Every field is always present; "no value to offer" is an explicit JSON `null`, because the worker
 * tests `!== null` before spreading a field into the mutation. For the three
 * server-owned fields (priority, tags, caregiverIds) a null means "leave the
 * server's copy alone", which is why callers pass them only when the caller chose
 * a value.
 *
 * `schedule` is the Convex `scheduleTimesValidator` shape (`{ type: "daily", ... }`),
 * not the PascalCase wire JSON the `schedule` column holds: the column is :core's
 * format, this is the server's.
 */
internal object SyncPayloads {
    fun scheduleForServer(schedule: ScheduleTimes): JsonObject =
        when (schedule) {
            is DailyTimes ->
                buildJsonObject {
                    put("type", "daily")
                    put("times", strings(schedule.times))
                }
            is IntervalTimes ->
                buildJsonObject {
                    put("type", "interval")
                    put("hours", schedule.hours)
                    put("startAt", schedule.startAt)
                }
            is WeeklyTimes ->
                buildJsonObject {
                    put("type", "weekly")
                    put("daysOfWeek", JsonArray(schedule.daysOfWeek.map(::JsonPrimitive)))
                    put("times", strings(schedule.times))
                }
            is OnceTimes ->
                buildJsonObject {
                    put("type", "once")
                    put("at", schedule.at)
                }
            NoneTimes -> buildJsonObject { put("type", "none") }
        }

    @Suppress("LongParameterList")
    fun reminder(
        localId: String,
        userId: String,
        kind: ReminderKind,
        name: String,
        rxcui: String?,
        dosage: String?,
        instructions: String?,
        schedule: ScheduleTimes,
        priority: Priority?,
        tags: List<String>?,
        pillsRemaining: Double?,
        pillsPerDose: Double?,
        refillReminderDays: Int?,
        caregiverIds: List<String>?,
        active: Boolean,
        createdAt: Long,
        updatedAt: Long,
    ): JsonObject =
        buildJsonObject {
            put("localId", localId)
            put("userId", userId)
            put("kind", kind.wire)
            put("name", name)
            put("rxcui", rxcui)
            put("dosage", dosage)
            put("instructions", instructions)
            put("schedule", scheduleForServer(schedule))
            put("priority", priority?.wire)
            put("tags", tags?.let(::strings) ?: JsonNull)
            put("pillsRemaining", pillsRemaining)
            put("pillsPerDose", pillsPerDose)
            put("refillReminderDays", refillReminderDays)
            put("caregiverIds", caregiverIds?.let(::strings) ?: JsonNull)
            put("active", active)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }

    fun reminderDelete(localId: String, userId: String, deletedAt: Long): JsonObject =
        buildJsonObject {
            put("localId", localId)
            put("userId", userId)
            put("deletedAt", deletedAt)
        }

    @Suppress("LongParameterList")
    fun instance(
        localId: String,
        userId: String,
        reminderId: String,
        reminderKind: ReminderKind,
        scheduledTime: Long,
        status: InstanceStatus,
        completedAt: Long?,
        snoozedUntil: Long?,
        snoozeCount: Int,
        notes: String?,
        createdAt: Long,
        updatedAt: Long,
    ): JsonObject =
        buildJsonObject {
            put("localId", localId)
            put("userId", userId)
            put("reminderId", reminderId)
            put("reminderKind", reminderKind.wire)
            put("scheduledTime", scheduledTime)
            put("status", status.wire)
            put("completedAt", completedAt)
            put("snoozedUntil", snoozedUntil)
            put("snoozeCount", snoozeCount)
            put("notes", notes)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }

    private fun strings(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
}
