package com.taskrx.core.golden

import com.taskrx.core.domain.NoticeRule
import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.PauseWindow
import com.taskrx.core.domain.SourceKind
import com.taskrx.core.domain.noticeRulesToJson
import com.taskrx.core.domain.overridesToJson
import com.taskrx.core.domain.pausesToJson
import com.taskrx.core.sync.PulledNotice
import com.taskrx.core.sync.PulledReminder
import com.taskrx.core.sync.PulledReminderInstance
import com.taskrx.core.sync.PulledWatchedSource
import com.taskrx.core.sync.ServerDocs
import com.taskrx.core.sync.scheduleToServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun strings(vararg s: String) = JsonArray(s.map(::JsonPrimitive))

private fun JsonObjectBuilder.putNullable(key: String, value: Any?) {
    put(
        key,
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is JsonElement -> value
            else -> error("no JSON for $value")
        },
    )
}

internal fun reminderOut(p: PulledReminder): JsonObject =
    buildJsonObject {
        put("convexId", p.convexId)
        putNullable("rxcui", p.rxcui)
        put(
            "reminder",
            buildJsonObject {
                val r = p.reminder
                put("id", r.id)
                put("userId", r.userId)
                put("kind", r.kind.wire)
                put("name", r.name)
                putNullable("dosage", r.dosage)
                putNullable("instructions", r.instructions)
                put("schedule", scheduleToServer(r.schedule))
                put("priority", r.priority.wire)
                put("tags", JsonArray(r.tags.map(::JsonPrimitive)))
                putNullable("pillsRemaining", r.pillsRemaining)
                putNullable("pillsPerDose", r.pillsPerDose)
                putNullable("refillReminderDays", r.refillReminderDays)
                put("caregiverIds", JsonArray(r.caregiverIds.map(::JsonPrimitive)))
                put("active", r.active)
                put("createdAt", r.createdAt)
                put("updatedAt", r.updatedAt)
                put("pauses", pausesToJson(r.pauses))
                put("overrides", overridesToJson(r.overrides))
                put("noticeRules", noticeRulesToJson(r.noticeRules))
            },
        )
    }

private fun instanceOut(p: PulledReminderInstance): JsonObject =
    buildJsonObject {
        put("convexId", p.convexId)
        put(
            "instance",
            buildJsonObject {
                val i = p.instance
                put("id", i.id)
                put("reminderId", i.reminderId)
                put("userId", i.userId)
                put("reminderKind", i.reminderKind.wire)
                put("scheduledTime", i.scheduledTime)
                put("status", i.status.wire)
                putNullable("completedAt", i.completedAt)
                putNullable("snoozedUntil", i.snoozedUntil)
                put("snoozeCount", i.snoozeCount)
                putNullable("notes", i.notes)
                put("createdAt", i.createdAt)
                put("updatedAt", i.updatedAt)
            },
        )
    }

private fun noticeOut(p: PulledNotice): JsonObject =
    buildJsonObject {
        put("convexId", p.convexId)
        put(
            "notice",
            buildJsonObject {
                val n = p.notice
                put("id", n.id)
                put("userId", n.userId)
                put("sourceKind", n.sourceKind.wire)
                put("sourceValue", n.sourceValue)
                put("title", n.title)
                putNullable("sentence", n.sentence)
                put("receivedAt", n.receivedAt)
                putNullable("externalId", n.externalId)
                put("resolution", n.resolution.wire)
                putNullable("resolvedAt", n.resolvedAt)
                put("createdAt", n.createdAt)
                put("updatedAt", n.updatedAt)
            },
        )
    }

private fun sourceOut(p: PulledWatchedSource): JsonObject =
    buildJsonObject {
        put("convexId", p.convexId)
        put(
            "source",
            buildJsonObject {
                val s = p.source
                put("id", s.id)
                put("userId", s.userId)
                put("kind", s.kind.wire)
                put("value", s.value)
                put("label", s.label)
                put("createdAt", s.createdAt)
                put("updatedAt", s.updatedAt)
            },
        )
    }

/**
 * The Convex documents as a client delivers them, read into the rows the rules work on:
 * `ServerDocs` (the phone's pull, and the desktop's read). Numbers arrive as float64 and are
 * spelled `1.7E12` as often as `1700000000000`; both are here.
 */
object ServerDocsGolden : GoldenSet {
    override val name = "server-docs"
    override val about =
        "ServerDocs: one Convex document as a client delivers it, read into the local row, or null when this build does not " +
            "know its kind, status, source kind or resolution. An instance's reminderId is the parent's server id, resolved " +
            "to its localId through parentLocalIds of the reminder documents; an unknown parent is null too. Unknown fields " +
            "are ignored; a schedule that cannot be read is `none`; an unknown priority is `normal`."

    private const val T = 1_780_000_000_000L

    private fun reminder(
        id: String = "j97rem1",
        localId: String = "rem-1",
        kind: String = "medication",
        build: JsonObjectBuilder.() -> Unit = {},
    ) = buildJsonObject {
        put("_id", id)
        put("_creationTime", 1.78E12)
        put("localId", localId)
        put("userId", "user_golden")
        put("kind", kind)
        put("name", "Atorvastatin")
        put("schedule", buildJsonObject {
            put("type", "daily")
            put("times", strings("09:00"))
        })
        put("priority", "normal")
        put("active", true)
        put("createdAt", T)
        put("updatedAt", T)
        build()
    }

    private val parents = listOf(reminder(), reminder(id = "j97rem2", localId = "rem-2", kind = "task"))

    private fun reminderCases(): List<GoldenCase> =
        listOf(
            "every field, numbers spelled as floats" to
                reminder {
                    put("_creationTime", 1.78E12)
                    put("rxcui", "617312")
                    put("dosage", "20mg")
                    put("instructions", "with food")
                    put("schedule", buildJsonObject {
                        put("type", "daily")
                        put("times", strings("09:00", "21:00"))
                    })
                    put("priority", "high")
                    put("tags", strings("heart"))
                    put("pillsRemaining", 28.0)
                    put("pillsPerDose", 1.5)
                    put("refillReminderDays", 7.0)
                    put("caregiverIds", strings("cg-1"))
                    put("createdAt", 1.78E12)
                    put("updatedAt", 1780000060000.0)
                    put("pauses", pausesToJson(listOf(PauseWindow(T + 86_400_000, T + 2 * 86_400_000), PauseWindow(T + 5 * 86_400_000))))
                    put("overrides", overridesToJson(listOf(OccurrenceOverride(T + 3_600_000, T + 7_200_000, noticeId = "notice-1"))))
                    put("noticeRules", noticeRulesToJson(listOf(NoticeRule(SourceKind.Email, "pharmacy@example.com", listOf("refill")))))
                },
            "only the required fields" to reminder(kind = "task"),
            "a field this build does not know is ignored" to reminder { put("color", "teal") },
            "an unknown kind is skipped" to reminder(kind = "habit"),
            "a schedule this build cannot read goes to the Backlog" to
                reminder {
                    put("schedule", buildJsonObject { put("type", "lunar") })
                },
            "an unknown priority reads as normal" to reminder { put("priority", "urgent") },
        ).map { (name, doc) ->
            GoldenCase(
                "reminder: $name",
                buildJsonObject {
                    put("call", "reminder")
                    put("doc", doc)
                },
                ServerDocs.toPulled(ServerDocs.reminders(JsonArray(listOf(doc))).single())?.let(::reminderOut) ?: JsonNull,
            )
        }

    private fun instance(
        reminderId: String = "j97rem1",
        status: String = "pending",
        kind: String = "medication",
        build: JsonObjectBuilder.() -> Unit = {},
    ) = buildJsonObject {
        put("_id", "j57inst1")
        put("_creationTime", 1.78E12)
        put("localId", "inst-1")
        put("userId", "user_golden")
        put("reminderId", reminderId)
        put("reminderKind", kind)
        put("scheduledTime", T)
        put("status", status)
        put("createdAt", T)
        put("updatedAt", T)
        build()
    }

    private fun instanceCases(): List<GoldenCase> =
        listOf(
            "a taking, numbers spelled as floats" to
                instance(status = "completed") {
                    put("scheduledTime", 1.78E12)
                    put("completedAt", 1780000300000.0)
                    put("snoozeCount", 0.0)
                    put("updatedAt", 1.7800003E12)
                },
            "a snooze with notes" to
                instance(status = "snoozed") {
                    put("snoozedUntil", T + 900_000)
                    put("snoozeCount", 2.0)
                    put("notes", "after lunch")
                },
            "a task's row, its parent resolved to the local id" to instance(reminderId = "j97rem2", kind = "task"),
            "only the required fields (no snoozeCount)" to instance(),
            "a parent that was not pulled is skipped" to instance(reminderId = "j97gone"),
            "an unknown status is skipped" to instance(status = "deferred"),
            "an unknown kind is skipped" to instance(kind = "habit"),
        ).map { (name, doc) ->
            GoldenCase(
                "instance: $name",
                buildJsonObject {
                    put("call", "instance")
                    put("doc", doc)
                    put("reminders", JsonArray(parents))
                },
                ServerDocs.toPulled(ServerDocs.instances(JsonArray(listOf(doc))).single(), ServerDocs.parentLocalIds(ServerDocs.reminders(JsonArray(parents))))
                    ?.let(::instanceOut) ?: JsonNull,
            )
        }

    private fun notice(
        sourceKind: String = "email",
        resolution: String = "none",
        build: JsonObjectBuilder.() -> Unit = {},
    ) = buildJsonObject {
        put("_id", "j77notice1")
        put("_creationTime", 1.78E12)
        put("localId", "notice-1")
        put("userId", "user_golden")
        put("sourceKind", sourceKind)
        put("sourceValue", "pharmacy@example.com")
        put("title", "Your refill is ready")
        put("receivedAt", 1.78E12)
        put("resolution", resolution)
        put("createdAt", T)
        put("updatedAt", T)
        build()
    }

    private fun noticeCases(): List<GoldenCase> =
        listOf(
            "every field" to
                notice(resolution = "applied") {
                    put("sentence", "Pick up Atorvastatin after 3pm Thursday")
                    put("externalId", "msg-42")
                    put("resolvedAt", 1780000600000.0)
                },
            "only the required fields" to notice(sourceKind = "app"),
            "an unknown source kind is skipped" to notice(sourceKind = "fax"),
            "an unknown resolution is skipped" to notice(resolution = "snoozed"),
        ).map { (name, doc) ->
            GoldenCase(
                "notice: $name",
                buildJsonObject {
                    put("call", "notice")
                    put("doc", doc)
                },
                ServerDocs.toPulled(ServerDocs.notices(JsonArray(listOf(doc))).single())?.let(::noticeOut) ?: JsonNull,
            )
        }

    private fun source(kind: String) =
        buildJsonObject {
            put("_id", "j87src1")
            put("_creationTime", 1.78E12)
            put("localId", "src-1")
            put("userId", "user_golden")
            put("kind", kind)
            put("value", "pharmacy@example.com")
            put("label", "Pharmacy")
            put("createdAt", 1.78E12)
            put("updatedAt", T)
        }

    private fun sourceCases(): List<GoldenCase> =
        listOf("a watched sender" to source("email"), "an unknown kind is skipped" to source("pager")).map { (name, doc) ->
            GoldenCase(
                "watchSource: $name",
                buildJsonObject {
                    put("call", "watchSource")
                    put("doc", doc)
                },
                ServerDocs.toPulled(ServerDocs.watchSources(JsonArray(listOf(doc))).single())?.let(::sourceOut) ?: JsonNull,
            )
        }

    override fun cases(): List<GoldenCase> = reminderCases() + instanceCases() + noticeCases() + sourceCases()
}
