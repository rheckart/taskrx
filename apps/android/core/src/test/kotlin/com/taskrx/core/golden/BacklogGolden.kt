package com.taskrx.core.golden

import com.taskrx.core.backlog.isBacklog
import com.taskrx.core.backlog.planBacklogCompletion
import com.taskrx.core.catchup.CATCH_UP_WINDOW_MS
import com.taskrx.core.catchup.catchUpDays
import com.taskrx.core.catchup.catchUpSlots
import com.taskrx.core.catchup.needsCatchUp
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.refill.DEFAULT_REFILL_REMINDER_DAYS
import com.taskrx.core.refill.RefillProjection
import com.taskrx.core.refill.projectRefill
import com.taskrx.core.timeline.TimelineRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

private const val NY = "America/New_York"

private fun ny(day: Int, hour: Int = 0, minute: Int = 0, month: Int = 7) = at(NY, 2026, month, day, hour, minute)

private fun daily(vararg t: String) =
    buildJsonObject {
        put("type", "daily")
        put("times", JsonArray(t.map(::JsonPrimitive)))
    }

private fun timelineRowJson(row: TimelineRow): JsonObject =
    buildJsonObject {
        put("reminderId", row.reminderId)
        put("instanceId", row.instanceId?.let(::JsonPrimitive) ?: JsonNull)
        put("name", row.name)
        put("dosage", row.dosage?.let(::JsonPrimitive) ?: JsonNull)
        put("kind", row.kind.wire)
        put("scheduledTime", row.scheduledTime)
        put("status", row.status.wire)
        put("snoozedUntil", row.snoozedUntil?.let(::JsonPrimitive) ?: JsonNull)
        put("isNext", row.isNext)
        put("completedAt", row.completedAt?.let(::JsonPrimitive) ?: JsonNull)
    }

/** The Backlog: what counts as a Backlog item, and the one row completing it writes. */
object BacklogGolden : GoldenSet {
    override val name = "backlog"
    override val about = "isBacklog(reminder) and planBacklogCompletion(reminder, completedAt); a scheduled reminder is refused"

    override fun cases(): List<GoldenCase> {
        val completedAt = ny(6, 14, 7)
        val reminders =
            listOf(
                "a Backlog task" to reminderDoc(kind = "task", dosage = null, name = "Sort the garage", schedule = buildJsonObject { put("type", "none") }),
                "a Backlog medication" to reminderDoc(schedule = buildJsonObject { put("type", "none") }),
                "an unreadable schedule reads as the Backlog" to reminderDoc(kind = "task", dosage = null, name = "Fix the gate", schedule = buildJsonObject { put("type", "fortnightly") }),
                "a daily task is refused" to reminderDoc(kind = "task", dosage = null, name = "Water the plants", schedule = daily("18:00")),
                "an as-needed medication is refused" to reminderDoc(schedule = buildJsonObject {
                    put("type", "asNeeded")
                    put("minGapMinutes", 240)
                }),
            )
        return reminders.map { (name, doc) ->
            val reminder = reminderFromDoc(doc)
            GoldenCase(
                name,
                buildJsonObject {
                    put("reminder", doc)
                    put("completedAt", completedAt)
                },
                buildJsonObject {
                    put("isBacklog", isBacklog(reminder))
                    put(
                        "completion",
                        outcome {
                            val c = planBacklogCompletion(reminder, completedAt)
                            buildJsonObject {
                                put("reminderId", c.reminderId)
                                put("reminderKind", c.reminderKind.wire)
                                put("scheduledTime", c.scheduledTime)
                                put("status", c.status.wire)
                                put("completedAt", c.completedAt)
                            }
                        },
                    )
                },
            )
        }
    }
}

/** Catch up: the misses worth a second look, grouped by local day (newest first) and by moment. */
object CatchUpGolden : GoldenSet {
    override val name = "catch-up"
    override val about = "needsCatchUp(rows), catchUpDays(rows, zone), catchUpSlots(rows) and CATCH_UP_WINDOW_MS"

    override fun cases(): List<GoldenCase> {
        fun row(id: String, name: String, time: Long, status: InstanceStatus, kind: ReminderKind = ReminderKind.Medication) =
            TimelineRow(id, if (status == InstanceStatus.Missed) "inst-$id-$time" else null, name, if (kind == ReminderKind.Medication) "1 tablet" else null, kind, time, status, null, false)
        val rows =
            listOf(
                row("med-a", "Atorvastatin", ny(5, 21), InstanceStatus.Missed),
                row("med-b", "Lisinopril", ny(5, 9), InstanceStatus.Missed),
                row("med-a", "Atorvastatin", ny(5, 9), InstanceStatus.Missed),
                row("med-a", "Atorvastatin", ny(6, 9), InstanceStatus.Completed),
                row("task-w", "Water the plants", ny(4, 18), InstanceStatus.Missed, ReminderKind.Task),
                row("med-b", "Lisinopril", ny(4, 9), InstanceStatus.Skipped),
                row("med-c", "Vitamin D", ny(6, 8), InstanceStatus.Snoozed),
                row("med-d", "Night dose", ny(5, 23, 59), InstanceStatus.Missed),
                row("med-e", "Early dose", ny(6, 0, 0), InstanceStatus.Missed),
            )
        val missed = needsCatchUp(rows)
        val input =
            buildJsonObject {
                put("rows", JsonArray(rows.map(::timelineRowJson)))
                put("zone", NY)
            }
        return listOf(
            GoldenCase("the catch-up window", buildJsonObject { put("call", "constants") }, buildJsonObject { put("windowMs", CATCH_UP_WINDOW_MS) }),
            GoldenCase("needsCatchUp: only misses", input, JsonArray(missed.map(::timelineRowJson))),
            GoldenCase(
                "catchUpDays: the misses by local day, newest day first",
                input,
                JsonArray(
                    catchUpDays(missed, ZoneId.of(NY)).map { day ->
                        buildJsonObject {
                            put("dayStart", day.dayStart)
                            put("rows", JsonArray(day.rows.map(::timelineRowJson)))
                        }
                    },
                ),
            ),
            GoldenCase(
                "catchUpDays: the same misses seen from Sydney",
                buildJsonObject {
                    put("rows", JsonArray(rows.map(::timelineRowJson)))
                    put("zone", "Australia/Sydney")
                },
                JsonArray(
                    catchUpDays(missed, ZoneId.of("Australia/Sydney")).map { day ->
                        buildJsonObject {
                            put("dayStart", day.dayStart)
                            put("rows", JsonArray(day.rows.map(::timelineRowJson)))
                        }
                    },
                ),
            ),
            GoldenCase(
                "catchUpSlots: the misses by moment, in first-seen order",
                input,
                JsonArray(
                    catchUpSlots(missed).map { (time, slot) ->
                        buildJsonObject {
                            put("scheduledTime", time)
                            put("rows", JsonArray(slot.map(::timelineRowJson)))
                        }
                    },
                ),
            ),
        )
    }
}

/** The refill outlook the Supplies screen shows. Spending a dose is the server's (ADR 0018). */
object RefillGolden : GoldenSet {
    override val name = "refill"
    override val about = "projectRefill(medication, now, zone): when the supply runs out and whether to refill"

    override fun cases(): List<GoldenCase> {
        val now = ny(6, 12)
        data class R(
            val name: String,
            val pills: Double? = 30.0,
            val perDose: Double? = 1.0,
            val schedule: JsonObject = daily("09:00", "21:00"),
            val kind: String = "medication",
            val active: Boolean = true,
            val refillDays: Int? = null,
            val zone: String = NY,
            val at: Long = now,
            val pauses: JsonArray? = null,
        )
        return listOf(
            R("thirty pills, one twice a day"),
            R("thirty pills, two at a time", perDose = 2.0),
            R("a fraction of a dose left over", pills = 7.5, perDose = 2.0),
            R("a half-tablet dose", pills = 10.0, perDose = 0.5),
            R("exactly enough for the warning window", pills = 14.0),
            R("one day inside the warning window", pills = 12.0),
            R("an empty bottle", pills = 0.0),
            R("a negative count reads as empty", pills = -3.0),
            R("a custom warning window", pills = 40.0, refillDays = 30),
            R("a warning window of zero", pills = 1.0, refillDays = 0),
            R("a supply that outlasts the walk", pills = 1_000_000.0),
            R("a weekly medication", pills = 3.0, schedule = buildJsonObject {
                put("type", "weekly")
                put("daysOfWeek", JsonArray(listOf(JsonPrimitive(1))))
                put("times", JsonArray(listOf(JsonPrimitive("08:00"))))
            }),
            R("an as-needed medication never runs out by schedule", schedule = buildJsonObject {
                put("type", "asNeeded")
                put("minGapMinutes", 240)
            }),
            R("a pause pushes the run-out later", pills = 4.0, pauses = JsonArray(listOf(buildJsonObject {
                put("from", ny(7))
                put("until", ny(10))
            }))),
            R("across the fall-back change", pills = 10.0, at = ny(28, 12, month = 10)),
            R("in Sydney", pills = 6.0, zone = "Australia/Sydney", at = at("Australia/Sydney", 2026, 7, 6, 12)),
            R("a task is not a medication", kind = "task"),
            R("a stopped medication", active = false),
            R("no supply recorded", pills = null),
            R("no dose size", perDose = null),
            R("a zero dose size", perDose = 0.0),
        ).map { r ->
            val doc =
                reminderDoc(
                    kind = r.kind,
                    dosage = if (r.kind == "task") null else "20mg",
                    schedule = r.schedule,
                    pillsRemaining = r.pills,
                    pillsPerDose = r.perDose,
                    active = r.active,
                    pauses = r.pauses,
                ).let { base ->
                    if (r.refillDays == null) base else JsonObject(base + ("refillReminderDays" to JsonPrimitive(r.refillDays)))
                }
            GoldenCase(
                r.name,
                buildJsonObject {
                    put("medication", doc)
                    put("now", r.at)
                    put("zone", r.zone)
                },
                when (val p = projectRefill(reminderFromDoc(doc), r.at, ZoneId.of(r.zone))) {
                    is RefillProjection.Unprojectable -> buildJsonObject { put("unprojectable", p.reason.wire) }
                    is RefillProjection.RefillOutlook ->
                        buildJsonObject {
                            put("occurrencesRemaining", p.occurrencesRemaining)
                            put("runsOutAt", p.runsOutAt?.let(::JsonPrimitive) ?: JsonNull)
                            put("daysRemaining", p.daysRemaining?.let(::JsonPrimitive) ?: JsonNull)
                            put("warningWindowDays", p.warningWindowDays)
                            put("needsRefill", p.needsRefill)
                        }
                },
            )
        } +
            GoldenCase(
                "the default warning window",
                buildJsonObject { put("call", "constants") },
                buildJsonObject { put("defaultRefillReminderDays", DEFAULT_REFILL_REMINDER_DAYS) },
            )
    }
}
