package com.taskrx.core.golden

import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.Priority
import com.taskrx.core.domain.Reminder
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.noticeRulesFromJson
import com.taskrx.core.domain.overridesFromJson
import com.taskrx.core.domain.pausesFromJson
import com.taskrx.core.sync.scheduleFromServer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.time.LocalDateTime
import java.time.ZoneId

/*
 * Golden fixtures (ADR 0017). `:core` is the reference for every rule the desktop Companion
 * ports to Rust. Each [GoldenSet] runs one rule over a table of inputs and records what
 * `:core` answered. `GoldenFixturesTest` writes the result to `apps/android/core/golden/` and,
 * in the gate, fails when the checked-in file differs, so a rule changed in Kotlin shows up as
 * a diff the Rust port has to follow.
 *
 * Inputs are in the shape the desktop receives: a Reminder is a Convex `reminders` document,
 * read by [reminderFromDoc] exactly as the phone's pull reads one. Every case that depends on
 * a zone names it, because the functions default to the JVM's zone and the desktop's is not
 * the test JVM's.
 */

interface GoldenSet {
    /** The file name, without `.json`. */
    val name: String

    /** What the cases exercise, for a reader of the JSON. */
    val about: String

    fun cases(): List<GoldenCase>
}

data class GoldenCase(val name: String, val input: JsonObject, val output: JsonElement)

/** Every set the gate checks. A new set is listed here or the gate fails on its file. */
val goldenSets: List<GoldenSet> =
    listOf(
        InstanceIdGolden,
        ScheduleGolden,
        ServerScheduleGolden,
        DomainGolden,
        TodayGolden,
        AnswerGolden,
        BacklogGolden,
        CatchUpGolden,
        RefillGolden,
    )

// ---- input helpers ------------------------------------------------------------------------

/** Epoch ms of a wall-clock time in [zone]; DST gaps resolve as `java.time` does (forward). */
fun at(zone: String, year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
    LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.of(zone)).toInstant().toEpochMilli()

/**
 * A Convex `reminders` document. Only the fields a rule reads are filled; the rest take the
 * values a fresh document has. [schedule] is in the server's shape (`scheduleTimesValidator`).
 */
fun reminderDoc(
    localId: String = "3f1c0a52-6a0e-4d8e-9a7f-2b1c9e0d4a11",
    kind: String = "medication",
    name: String = "Atorvastatin",
    dosage: String? = "20mg",
    schedule: JsonObject,
    priority: String = "normal",
    pillsRemaining: Double? = null,
    pillsPerDose: Double? = null,
    active: Boolean = true,
    createdAt: Long = 1_700_000_000_000,
    updatedAt: Long = createdAt,
    pauses: JsonArray? = null,
    overrides: JsonArray? = null,
): JsonObject =
    buildJsonObject {
        put("_id", "j97doc${localId.take(8)}")
        put("localId", localId)
        put("userId", "user_golden")
        put("kind", kind)
        put("name", name)
        dosage?.let { put("dosage", it) }
        put("schedule", schedule)
        put("priority", priority)
        put("tags", JsonArray(emptyList()))
        pillsRemaining?.let { put("pillsRemaining", it) }
        pillsPerDose?.let { put("pillsPerDose", it) }
        put("caregiverIds", JsonArray(emptyList()))
        put("active", active)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        pauses?.let { put("pauses", it) }
        overrides?.let { put("overrides", it) }
    }

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull || it.isString }?.double

/** The phone's `ServerDocs.toPulled`, over `:core`'s own readers. */
fun reminderFromDoc(doc: JsonObject): Reminder =
    Reminder(
        id = doc.text("localId")!!,
        userId = doc.text("userId")!!,
        kind = ReminderKind.fromWire(doc.text("kind")!!)!!,
        name = doc.text("name")!!,
        dosage = doc.text("dosage"),
        instructions = doc.text("instructions"),
        schedule = scheduleFromServer(doc["schedule"]) ?: NoneTimes,
        priority = Priority.fromWire(doc.text("priority")!!) ?: Priority.Normal,
        tags = doc["tags"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        pillsRemaining = doc.number("pillsRemaining"),
        pillsPerDose = doc.number("pillsPerDose"),
        refillReminderDays = doc.number("refillReminderDays")?.toInt(),
        caregiverIds = doc["caregiverIds"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
        active = doc["active"]!!.jsonPrimitive.boolean,
        createdAt = doc["createdAt"]!!.jsonPrimitive.long,
        updatedAt = doc["updatedAt"]!!.jsonPrimitive.long,
        pauses = pausesFromJson(doc["pauses"]),
        overrides = overridesFromJson(doc["overrides"]),
        noticeRules = noticeRulesFromJson(doc["noticeRules"]),
    )

/** The outcome of a call that may refuse its input: a value, or the kind of refusal. */
inline fun outcome(block: () -> JsonElement): JsonElement =
    try {
        block()
    } catch (e: IllegalArgumentException) {
        buildJsonObject { put("refused", "invalidArgument") }
    }
