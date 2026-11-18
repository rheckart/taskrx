package com.taskrx.core.sync

import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.NoticeRule
import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.noticeRulesToJson
import com.taskrx.core.domain.overridesToJson
import com.taskrx.core.domain.pausesToJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The JSON a `sync_queue` row carries, shaped for what the sync worker pushes into
 * `api.reminders.create/update/delete` and `api.reminderInstances.create/update`
 * (archive/expo-rn:apps/mobile/src/sync/worker.ts `ReminderPayload` / `ReminderInstancePayload`).
 *
 * Every field is always present; "no value to offer" is an explicit JSON `null`, because the worker
 * tests `!== null` before spreading a field into the mutation. For the three
 * server-owned fields (priority, tags, caregiverIds) a null means "leave the
 * server's copy alone", which is why callers pass them only when the caller chose
 * a value.
 *
 * Shared with the desktop Companion (ADR 0017), and pinned with the calls `Push.kt` makes of them
 * by the `push` golden fixtures.
 *
 * `schedule` is the Convex `scheduleTimesValidator` shape (`{ type: "daily", ... }`),
 * not the PascalCase wire JSON the `schedule` column holds: the column is :core's
 * format, this is the server's.
 */
object SyncPayloads {
    /** The Convex shape; the rule is `:core`'s, shared with the desktop Companion. */
    fun scheduleForServer(schedule: ScheduleTimes): JsonObject = scheduleToServer(schedule)

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
        pauses: List<PauseWindow>? = null,
        overrides: List<OccurrenceOverride>? = null,
        noticeRules: List<NoticeRule>? = null,
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
            put("tags", if (tags == null) JsonNull else strings(tags))
            put("pillsRemaining", pillsRemaining)
            put("pillsPerDose", pillsPerDose)
            put("refillReminderDays", refillReminderDays)
            put("caregiverIds", if (caregiverIds == null) JsonNull else strings(caregiverIds))
            put("active", active)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            // Null leaves the server's windows alone; an empty list clears them.
            put("pauses", if (pauses == null) JsonNull else pausesToJson(pauses))
            // Same rule for moved occurrences.
            put("overrides", if (overrides == null) JsonNull else overridesToJson(overrides))
            // And for the rules that let a notice be proposed against this Reminder.
            put("noticeRules", if (noticeRules == null) JsonNull else noticeRulesToJson(noticeRules))
        }

    fun reminderDelete(localId: String, userId: String, deletedAt: Long): JsonObject =
        buildJsonObject {
            put("localId", localId)
            put("userId", userId)
            put("deletedAt", deletedAt)
        }

    /** One notice, as `notices.create` takes it. */
    fun notice(
        localId: String,
        userId: String,
        sourceKind: SourceKind,
        sourceValue: String,
        title: String,
        sentence: String?,
        receivedAt: Long,
        externalId: String?,
        resolution: NoticeResolution,
        resolvedAt: Long?,
        createdAt: Long,
        updatedAt: Long,
    ): JsonObject =
        buildJsonObject {
            put("localId", localId)
            put("userId", userId)
            put("sourceKind", sourceKind.wire)
            put("sourceValue", sourceValue)
            put("title", title)
            put("sentence", sentence)
            put("receivedAt", receivedAt)
            put("externalId", externalId)
            put("resolution", resolution.wire)
            put("resolvedAt", resolvedAt)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }

    /** One watched source, as `watchSources.create` takes it. */
    fun watchSource(
        localId: String,
        userId: String,
        kind: SourceKind,
        value: String,
        label: String,
        createdAt: Long,
        updatedAt: Long,
    ): JsonObject =
        buildJsonObject {
            put("localId", localId)
            put("userId", userId)
            put("kind", kind.wire)
            put("value", value)
            put("label", label)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
        }

    /** A notice removal, as `notices.remove` takes it. [convexId] is carried because the local row is gone by then. */
    fun noticeDelete(userId: String, deletedAt: Long, convexId: String? = null): JsonObject =
        buildJsonObject {
            put("userId", userId)
            put("deletedAt", deletedAt)
            convexId?.let { put("convexId", it) }
        }

    /** A watched-source removal, as `watchSources.remove` takes it. */
    fun watchSourceDelete(userId: String, deletedAt: Long, convexId: String? = null): JsonObject =
        buildJsonObject {
            put("userId", userId)
            put("deletedAt", deletedAt)
            convexId?.let { put("convexId", it) }
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
