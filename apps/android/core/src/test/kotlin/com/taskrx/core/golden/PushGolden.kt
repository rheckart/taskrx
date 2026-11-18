package com.taskrx.core.golden

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.NoticeResolution
import com.taskrx.core.domain.NoticeRule
import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.dailyTimes
import com.taskrx.core.domain.onceTimes
import com.taskrx.core.sync.PushCall
import com.taskrx.core.sync.SyncPayloads
import com.taskrx.core.sync.instanceCreateCall
import com.taskrx.core.sync.instanceUpdateCall
import com.taskrx.core.sync.noticeCreateCall
import com.taskrx.core.sync.noticeDeleteCall
import com.taskrx.core.sync.noticeUpdateCall
import com.taskrx.core.sync.reminderCreateCall
import com.taskrx.core.sync.reminderDeleteCall
import com.taskrx.core.sync.reminderUpdateCall
import com.taskrx.core.sync.watchSourceCreateCall
import com.taskrx.core.sync.watchSourceDeleteCall
import com.taskrx.core.sync.watchSourceUpdateCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A call's arguments as JSON: every number the client sends is a float64. */
private fun wire(value: Any?): JsonElement =
    when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k as String to wire(v) })
        is List<*> -> JsonArray(value.map(::wire))
        else -> error("the client sends no ${value::class.simpleName}")
    }

private fun callJson(call: PushCall): JsonObject =
    buildJsonObject {
        put("function", call.function)
        put("args", wire(call.args))
    }

/**
 * What a write sends: the queued payload `SyncPayloads` builds from a row's fields (every field
 * present, "no value" an explicit null), and the Convex call `Push.kt` makes of it (a null left
 * out: "leave the server's copy alone"). The desktop writes the same calls.
 */
object PushGolden : GoldenSet {
    override val name = "push"
    override val about =
        "SyncPayloads then Push: `payload` is the fields of a write (enums as wire strings, the schedule in the server's " +
            "shape, absent values null), `call` the Convex function and arguments sent for it. Every number in `args` is a " +
            "float64. An update or delete names the document by its server id; an instance's create names its parent's."

    private const val T = 1_780_000_000_000L

    /**
     * [entity] and [operation] say what is written; [convexId] is the document's own server id
     * (an update or delete), [parentConvexId] its parent's (an instance's create).
     */
    private fun case(
        name: String,
        entity: String,
        operation: String,
        payload: JsonObject,
        call: PushCall,
        convexId: String? = null,
        parentConvexId: String? = null,
    ) = GoldenCase(
        name,
        buildJsonObject {
            put("entity", entity)
            put("operation", operation)
            put("payload", payload)
            convexId?.let { put("convexId", it) }
            parentConvexId?.let { put("parentConvexId", it) }
        },
        callJson(call),
    )

    private val fullReminder =
        SyncPayloads.reminder(
            localId = "rem-1",
            userId = "user_golden",
            kind = ReminderKind.Medication,
            name = "Atorvastatin",
            rxcui = "617312",
            dosage = "20mg",
            instructions = "with food",
            schedule = dailyTimes(listOf("09:00", "21:00")),
            priority = Priority.High,
            tags = listOf("heart"),
            pillsRemaining = 28.0,
            pillsPerDose = 1.5,
            refillReminderDays = 7,
            caregiverIds = listOf("cg-1"),
            active = true,
            createdAt = T,
            updatedAt = T + 60_000,
            pauses = listOf(PauseWindow(T + 86_400_000, T + 2 * 86_400_000)),
            overrides = listOf(OccurrenceOverride(T + 3_600_000, T + 7_200_000)),
            noticeRules = listOf(NoticeRule(SourceKind.Email, "pharmacy@example.com", listOf("refill"))),
        )

    private val bareTask =
        SyncPayloads.reminder(
            localId = "rem-2",
            userId = "user_golden",
            kind = ReminderKind.Task,
            name = "Call the pharmacy",
            rxcui = null,
            dosage = null,
            instructions = null,
            schedule = onceTimes(T + 86_400_000),
            priority = null,
            tags = null,
            pillsRemaining = null,
            pillsPerDose = null,
            refillReminderDays = null,
            caregiverIds = null,
            active = false,
            createdAt = T,
            updatedAt = T,
        )

    private fun reminderCases(): List<GoldenCase> {
        val delete = SyncPayloads.reminderDelete("rem-1", "user_golden", T + 120_000)
        return listOf(
            case("reminders:create, every field", "reminder", "create", fullReminder, reminderCreateCall(fullReminder)),
            case("reminders:create, nothing optional (nulls are left out)", "reminder", "create", bareTask, reminderCreateCall(bareTask)),
            case("reminders:update, every field", "reminder", "update", fullReminder, reminderUpdateCall(fullReminder, "j97rem1"), convexId = "j97rem1"),
            case("reminders:update, nothing optional", "reminder", "update", bareTask, reminderUpdateCall(bareTask, "j97rem2"), convexId = "j97rem2"),
            case("reminders:softDelete", "reminder", "delete", delete, reminderDeleteCall("j97rem1"), convexId = "j97rem1"),
        )
    }

    private fun instanceCases(): List<GoldenCase> {
        fun payload(status: InstanceStatus, completedAt: Long? = null, snoozedUntil: Long? = null, snoozeCount: Int = 0, notes: String? = null) =
            SyncPayloads.instance(
                localId = "inst-1",
                userId = "user_golden",
                reminderId = "rem-1",
                reminderKind = ReminderKind.Medication,
                scheduledTime = T,
                status = status,
                completedAt = completedAt,
                snoozedUntil = snoozedUntil,
                snoozeCount = snoozeCount,
                notes = notes,
                createdAt = T,
                updatedAt = T + 300_000,
            )
        val taken = payload(InstanceStatus.Completed, completedAt = T + 300_000, notes = "after lunch")
        val snoozed = payload(InstanceStatus.Snoozed, snoozedUntil = T + 900_000, snoozeCount = 1)
        val skipped = payload(InstanceStatus.Skipped)
        return listOf(
            case("reminderInstances:create, a taking (the parent named by its server id)", "instance", "create", taken, instanceCreateCall(taken, "j97rem1"), parentConvexId = "j97rem1"),
            case("reminderInstances:create, a skip", "instance", "create", skipped, instanceCreateCall(skipped, "j97rem1"), parentConvexId = "j97rem1"),
            case("reminderInstances update, a snooze", "instance", "update", snoozed, instanceUpdateCall(snoozed, "j57inst1"), convexId = "j57inst1"),
            case("reminderInstances update, a taking", "instance", "update", taken, instanceUpdateCall(taken, "j57inst1"), convexId = "j57inst1"),
            case("reminderInstances update, a skip", "instance", "update", skipped, instanceUpdateCall(skipped, "j57inst1"), convexId = "j57inst1"),
            case("reminderInstances update, reopened", "instance", "update", payload(InstanceStatus.Pending), instanceUpdateCall(payload(InstanceStatus.Pending), "j57inst1"), convexId = "j57inst1"),
            case("reminderInstances update, missed", "instance", "update", payload(InstanceStatus.Missed), instanceUpdateCall(payload(InstanceStatus.Missed), "j57inst1"), convexId = "j57inst1"),
        )
    }

    private fun noticeCases(): List<GoldenCase> {
        val full =
            SyncPayloads.notice(
                localId = "notice-1",
                userId = "user_golden",
                sourceKind = SourceKind.Email,
                sourceValue = "pharmacy@example.com",
                title = "Your refill is ready",
                sentence = "Pick up Atorvastatin after 3pm Thursday",
                receivedAt = T,
                externalId = "msg-42",
                resolution = NoticeResolution.Applied,
                resolvedAt = T + 600_000,
                createdAt = T,
                updatedAt = T + 600_000,
            )
        val bare =
            SyncPayloads.notice(
                localId = "notice-2",
                userId = "user_golden",
                sourceKind = SourceKind.Share,
                sourceValue = "share",
                title = "Shared text",
                sentence = null,
                receivedAt = T,
                externalId = null,
                resolution = NoticeResolution.None,
                resolvedAt = null,
                createdAt = T,
                updatedAt = T,
            )
        val removeKnown = SyncPayloads.noticeDelete("user_golden", T + 700_000, convexId = "j77notice1")
        val removeLooked = SyncPayloads.noticeDelete("user_golden", T + 700_000)
        return listOf(
            case("notices:create, every field", "notice", "create", full, noticeCreateCall(full)),
            case("notices:create, nothing optional", "notice", "create", bare, noticeCreateCall(bare)),
            case("notices:update", "notice", "update", full, noticeUpdateCall(full, "j77notice1"), convexId = "j77notice1"),
            case("notices:remove, the server id carried in the payload", "notice", "delete", removeKnown, noticeDeleteCall("j77notice1"), convexId = "j77notice1"),
            case("notices:remove, the server id looked up", "notice", "delete", removeLooked, noticeDeleteCall("j77notice2"), convexId = "j77notice2"),
        )
    }

    private fun sourceCases(): List<GoldenCase> {
        val source = SyncPayloads.watchSource("src-1", "user_golden", SourceKind.Email, "pharmacy@example.com", "Pharmacy", T, T + 60_000)
        val removeKnown = SyncPayloads.watchSourceDelete("user_golden", T + 120_000, convexId = "j87src1")
        val removeLooked = SyncPayloads.watchSourceDelete("user_golden", T + 120_000)
        return listOf(
            case("watchSources:create", "watchSource", "create", source, watchSourceCreateCall(source)),
            case("watchSources:update", "watchSource", "update", source, watchSourceUpdateCall(source, "j87src1"), convexId = "j87src1"),
            case("watchSources:remove, the server id carried in the payload", "watchSource", "delete", removeKnown, watchSourceDeleteCall("j87src1"), convexId = "j87src1"),
            case("watchSources:remove, the server id looked up", "watchSource", "delete", removeLooked, watchSourceDeleteCall("j87src2"), convexId = "j87src2"),
        )
    }

    override fun cases(): List<GoldenCase> = reminderCases() + instanceCases() + noticeCases() + sourceCases()
}
