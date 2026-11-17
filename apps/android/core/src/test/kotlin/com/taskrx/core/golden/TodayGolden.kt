package com.taskrx.core.golden

import com.taskrx.core.batch.BatchAction
import com.taskrx.core.batch.batchNotificationId
import com.taskrx.core.batch.batchSlots
import com.taskrx.core.batch.batchText
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.sweep.GRACE_PERIOD_MS
import com.taskrx.core.sweep.MissedVerdict
import com.taskrx.core.sweep.PlanMissedSweepInput
import com.taskrx.core.sweep.SWEEP_LOOKBACK_MS
import com.taskrx.core.sweep.planMissedSweep
import com.taskrx.core.sweep.snoozeRanOut
import com.taskrx.core.tasks.TaskSections
import com.taskrx.core.tasks.sectionTasks
import com.taskrx.core.time.localDayBounds
import com.taskrx.core.timeline.ProjectTimelineInput
import com.taskrx.core.timeline.TimelineRow
import com.taskrx.core.timeline.TimelineWindow
import com.taskrx.core.timeline.projectTimeline
import com.taskrx.core.undo.UndoVerdict
import com.taskrx.core.undo.isScheduledOccurrence
import com.taskrx.core.undo.undoVerdict
import com.taskrx.core.undo.undoWillBeMissed
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

private const val NY = "America/New_York"
private val ZONE: ZoneId = ZoneId.of(NY)

private fun ny(day: Int, hour: Int = 0, minute: Int = 0, month: Int = 7) = at(NY, 2026, month, day, hour, minute)

private fun schedule(type: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
    buildJsonObject {
        put("type", type)
        build()
    }

private fun times(vararg t: String) = JsonArray(t.map(::JsonPrimitive))

private fun ints(vararg i: Int) = JsonArray(i.map(::JsonPrimitive))

/**
 * One person's week around Monday 6 July 2026 in New York: medications and tasks of every
 * Schedule, a paused and a stopped one, an as-needed dose, a Backlog task, answers of every
 * status, a snooze nobody came back to, and two legacy rows at one moment.
 */
private object World {
    val created = ny(1, month = 6)

    private fun med(id: String, name: String, s: JsonObject, active: Boolean = true, pauses: JsonArray? = null) =
        reminderDoc(localId = id, kind = "medication", name = name, dosage = "1 tablet", schedule = s, active = active, pauses = pauses, createdAt = created)

    private fun task(id: String, name: String, s: JsonObject, createdAt: Long = created) =
        reminderDoc(localId = id, kind = "task", name = name, dosage = null, schedule = s, createdAt = createdAt)

    val reminders: List<JsonObject> =
        listOf(
            med("med-atorvastatin", "Atorvastatin", schedule("daily") { put("times", times("09:00", "21:00")) }),
            med("med-lisinopril", "Lisinopril", schedule("daily") { put("times", times("09:00")) }),
            med("med-vitamin-d", "Vitamin D", schedule("weekly") {
                put("daysOfWeek", ints(1))
                put("times", times("08:00"))
            }),
            med("med-paused", "Prednisone", schedule("daily") { put("times", times("13:00")) }, pauses = buildPause(ny(6, 11), null)),
            med("med-stopped", "Amoxicillin", schedule("daily") { put("times", times("07:00")) }, active = false),
            med("med-ibuprofen", "Ibuprofen", schedule("asNeeded") {
                put("minGapMinutes", 240)
                put("maxPer24h", 4)
            }),
            task("task-water", "Water the plants", schedule("daily") { put("times", times("18:00")) }),
            task("task-rent", "Pay rent", schedule("monthly") {
                put("daysOfMonth", ints(1))
                put("times", times("10:00"))
            }),
            task("task-pharmacy", "Call the pharmacy", schedule("once") { put("at", ny(6, 15)) }),
            task("task-stretch", "Stretch", schedule("interval") {
                put("hours", 8)
                put("startAt", ny(3, 7))
            }),
            task("task-backlog", "Sort the garage", schedule("none")),
            task("task-new", "Journal", schedule("daily") { put("times", times("08:00")) }, createdAt = ny(5, 20)),
        )

    val instances: List<JsonObject> =
        listOf(
            instanceDoc("med-atorvastatin", ny(6, 9), "completed", kind = "medication", completedAt = ny(6, 9, 5)),
            instanceDoc("med-lisinopril", ny(6, 9), "skipped", kind = "medication"),
            instanceDoc("med-atorvastatin", ny(5, 21), "missed", kind = "medication"),
            instanceDoc("med-vitamin-d", ny(6, 8), "snoozed", kind = "medication", snoozedUntil = ny(6, 8, 30)),
            instanceDoc("med-stopped", ny(6, 7), "completed", kind = "medication", completedAt = ny(6, 7, 10)),
            instanceDoc("med-ibuprofen", ny(6, 10, 15), "completed", kind = "medication", completedAt = ny(6, 10, 15)),
            instanceDoc("med-ibuprofen", ny(6, 4, 30), "completed", kind = "medication", completedAt = ny(6, 4, 30)),
            instanceDoc("task-water", ny(5, 18), "pending"),
            instanceDoc("task-water", ny(4, 18), "completed", completedAt = ny(4, 19)),
            instanceDoc("task-stretch", ny(5, 23), "snoozed", snoozedUntil = ny(6, 13)),
            // Two rows at one moment, minted before ids were derived (ADR 0018).
            instanceDoc("med-lisinopril", ny(5, 9), "missed", kind = "medication", localId = "legacy-a"),
            instanceDoc("med-lisinopril", ny(5, 9), "completed", kind = "medication", completedAt = ny(5, 9, 40), localId = "legacy-b"),
        )

    private fun buildPause(from: Long, until: Long?) =
        JsonArray(
            listOf(
                buildJsonObject {
                    put("from", from)
                    until?.let { put("until", it) }
                },
            ),
        )
}

private fun rowJson(row: TimelineRow): JsonObject =
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

private fun rowFromJson(o: JsonObject): TimelineRow {
    fun text(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun long(k: String) = (o[k] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.toLong()
    return TimelineRow(
        reminderId = text("reminderId")!!,
        instanceId = text("instanceId"),
        name = text("name")!!,
        dosage = text("dosage"),
        kind = ReminderKind.fromWire(text("kind")!!)!!,
        scheduledTime = long("scheduledTime")!!,
        status = InstanceStatus.fromWire(text("status")!!)!!,
        snoozedUntil = long("snoozedUntil"),
        isNext = (o["isNext"] as JsonPrimitive).content.toBooleanStrict(),
        completedAt = long("completedAt"),
    )
}

private fun sectionsJson(s: TaskSections): JsonObject =
    buildJsonObject {
        put("overdue", JsonArray(s.overdue.map(::rowJson)))
        put("dueToday", JsonArray(s.dueToday.map(::rowJson)))
        put(
            "upcoming",
            JsonArray(
                s.upcoming.map { day ->
                    buildJsonObject {
                        put("dayStart", day.dayStart)
                        put("rows", JsonArray(day.rows.map(::rowJson)))
                    }
                },
            ),
        )
    }

private fun verdictJson(v: MissedVerdict): JsonObject =
    buildJsonObject {
        put("reminderId", v.reminderId)
        put("reminderKind", v.reminderKind.wire)
        put("scheduledTime", v.scheduledTime)
        put("instanceId", v.instanceId?.let(::JsonPrimitive) ?: JsonNull)
    }

/**
 * Today and the Tasks tab: `projectTimeline`, `sectionTasks`, `batchSlots`, `planMissedSweep`
 * (the desktop shows the sweep's verdicts but never writes them; ADR 0016), `snoozeRanOut` and
 * `undoVerdict`. The timeline and sweep cases run over one week of one person's data.
 */
object TodayGolden : GoldenSet {
    override val name = "today"
    override val about =
        "projectTimeline, sectionTasks, batchSlots/batchText/batchNotificationId, planMissedSweep, snoozeRanOut, " +
            "undoVerdict/isScheduledOccurrence/undoWillBeMissed. Reminders and instances are Convex documents " +
            "(an instance's reminderId already resolved to the parent's localId)."

    private val reminders = World.reminders.map(::reminderFromDoc)
    private val instances = World.instances.map(::instanceFromDoc)

    private fun project(start: Long, end: Long, now: Long, max: Int, kinds: Set<ReminderKind> = ReminderKind.entries.toSet()): List<TimelineRow> =
        projectTimeline(
            ProjectTimelineInput(
                reminders.filter { it.kind in kinds },
                instances.filter { it.reminderKind in kinds },
                TimelineWindow(start, end),
                now,
                ZONE,
                max,
            ),
        )

    private fun timelineCases(): List<GoldenCase> {
        data class T(val name: String, val now: Long, val dayOffset: Int, val max: Int = 64, val kinds: Set<ReminderKind> = ReminderKind.entries.toSet())
        return listOf(
            T("timeline: Monday at noon", ny(6, 12), 0),
            T("timeline: Monday before anything is due", ny(6, 6), 0),
            T("timeline: Monday exactly at 09:00", ny(6, 9), 0),
            T("timeline: Monday late evening", ny(6, 23), 0),
            T("timeline: the day before, seen from Monday noon", ny(6, 12), -1),
            T("timeline: the day after, seen from Monday noon", ny(6, 12), 1),
            T("timeline: a week back", ny(6, 12), -6),
            T("timeline: one occurrence per reminder at most", ny(6, 12), 0, max = 1),
            T("timeline: tasks only", ny(6, 12), 0, kinds = setOf(ReminderKind.Task)),
        ).map { t ->
            val day = localDayBounds(t.now, t.dayOffset, ZONE)
            GoldenCase(
                t.name,
                buildJsonObject {
                    put("call", "projectTimeline")
                    put("reminders", JsonArray(World.reminders.filter { r -> reminderFromDoc(r).kind in t.kinds }))
                    put("instances", JsonArray(World.instances.filter { i -> instanceFromDoc(i).reminderKind in t.kinds }))
                    put("windowStart", day.start)
                    put("windowEnd", day.end)
                    put("now", t.now)
                    put("zone", NY)
                    put("maxOccurrencesPerReminder", t.max)
                },
                JsonArray(project(day.start, day.end, t.now, t.max, t.kinds).map(::rowJson)),
            )
        }
    }

    /** Two rows at one moment (legacy, ADR 0018): which one the timeline shows. */
    private fun duplicateCases(): List<GoldenCase> {
        val med = World.reminders.first { (it["localId"] as JsonPrimitive).content == "med-lisinopril" }
        val t = ny(6, 9)
        fun row(status: String, id: String, updated: Long = t) = instanceDoc("med-lisinopril", t, status, kind = "medication", localId = id, updatedAt = updated, completedAt = if (status == "completed") t else null)
        return listOf(
            "an answer beats an open row" to listOf(row("pending", "a"), row("skipped", "b")),
            "an answer beats an open row, either order" to listOf(row("completed", "a"), row("pending", "b")),
            "between two answers the later write wins" to listOf(row("completed", "a", t + 5), row("skipped", "b", t + 9)),
            "between two answers the later write wins, either order" to listOf(row("skipped", "a", t + 9), row("completed", "b", t + 5)),
            "a tie goes to the smaller id" to listOf(row("missed", "b"), row("completed", "a")),
            "two open rows tie on id" to listOf(row("pending", "z"), row("snoozed", "y")),
            "an instance whose reminder is gone is left out" to listOf(instanceDoc("med-deleted", t, "completed", kind = "medication", completedAt = t)),
        ).map { (name, docs) ->
            val day = localDayBounds(ny(6, 12), 0, ZONE)
            GoldenCase(
                "timeline duplicates: $name",
                buildJsonObject {
                    put("call", "projectTimeline")
                    put("reminders", JsonArray(listOf(med)))
                    put("instances", JsonArray(docs))
                    put("windowStart", day.start)
                    put("windowEnd", day.end)
                    put("now", ny(6, 12))
                    put("zone", NY)
                    put("maxOccurrencesPerReminder", 64)
                },
                JsonArray(
                    projectTimeline(ProjectTimelineInput(listOf(reminderFromDoc(med)), docs.map(::instanceFromDoc), TimelineWindow(day.start, day.end), ny(6, 12), ZONE, 64))
                        .map(::rowJson),
                ),
            )
        }
    }

    /** Rows the projection does not produce on this week, written out: a missed task and one beyond the horizon. */
    private fun handRowCases(): List<GoldenCase> {
        val now = ny(6, 12)
        fun row(name: String, time: Long, status: InstanceStatus, kind: ReminderKind = ReminderKind.Task) =
            TimelineRow("task-$name", if (status == InstanceStatus.Pending) null else "inst-$name", name, null, kind, time, status, null, false, if (status == InstanceStatus.Completed) time else null)
        val rows =
            listOf(
                row("missed-last-week", ny(1, 9), InstanceStatus.Missed),
                row("pending-yesterday", ny(5, 9), InstanceStatus.Pending),
                row("skipped-yesterday", ny(5, 10), InstanceStatus.Skipped),
                row("completed-this-morning", ny(6, 8), InstanceStatus.Completed),
                row("missed-this-morning", ny(6, 9), InstanceStatus.Missed),
                row("later-today", ny(6, 17), InstanceStatus.Pending),
                row("snoozed-today", ny(6, 11), InstanceStatus.Snoozed),
                row("in-six-days", ny(12, 9), InstanceStatus.Pending),
                row("at-the-horizon-end", localDayBounds(now, 7, ZONE).end, InstanceStatus.Pending),
                row("past-the-horizon", ny(14, 9), InstanceStatus.Pending),
                row("a-medication", ny(6, 9), InstanceStatus.Pending, ReminderKind.Medication),
            )
        // A week whose horizon crosses the fall-back change: 1 November is 25 hours long.
        val fallNow = at(NY, 2026, 10, 29, 12)
        fun fallRow(name: String, day: Int, month: Int = 11) =
            TimelineRow("task-$name", null, name, null, ReminderKind.Task, at(NY, 2026, month, day, 23, 30), InstanceStatus.Pending, null, false)
        val fallRows =
            listOf(fallRow("oct-31", 31, 10), fallRow("nov-1", 1), fallRow("nov-4", 4), fallRow("nov-5", 5), fallRow("nov-6", 6))
        return listOf(
            GoldenCase(
                "sectionTasks: a week across the fall-back change",
                buildJsonObject {
                    put("call", "sectionTasks")
                    put("rows", JsonArray(fallRows.map(::rowJson)))
                    put("now", fallNow)
                    put("zone", NY)
                },
                sectionsJson(sectionTasks(fallRows, fallNow, ZONE)),
            ),
            GoldenCase(
                "sectionTasks: rows of every status around the week",
                buildJsonObject {
                    put("call", "sectionTasks")
                    put("rows", JsonArray(rows.map(::rowJson)))
                    put("now", now)
                    put("zone", NY)
                },
                sectionsJson(sectionTasks(rows, now, ZONE)),
            ),
        )
    }

    private fun taskCases(): List<GoldenCase> =
        listOf(ny(6, 12), ny(6, 19), ny(7, 0, 30), ny(1, 9)).map { now ->
            // The Tasks tab's fortnight, as the phone projects it.
            val rows = project(localDayBounds(now, -7, ZONE).start, localDayBounds(now, 7, ZONE).end, now, 512, setOf(ReminderKind.Task))
            val withAMedication = rows + project(localDayBounds(now, 0, ZONE).start, localDayBounds(now, 0, ZONE).end, now, 64, setOf(ReminderKind.Medication)).take(1)
            GoldenCase(
                "sectionTasks: at ${java.time.Instant.ofEpochMilli(now).atZone(ZONE).toLocalDateTime()}",
                buildJsonObject {
                    put("call", "sectionTasks")
                    put("rows", JsonArray(withAMedication.map(::rowJson)))
                    put("now", now)
                    put("zone", NY)
                },
                sectionsJson(sectionTasks(withAMedication.map(::rowJson).map(::rowFromJson), now, ZONE)),
            )
        }

    private fun batchCases(): List<GoldenCase> {
        val day = localDayBounds(ny(6, 12), 0, ZONE)
        val monday = project(day.start, day.end, ny(6, 6), 64)
        val slots =
            listOf(
                "batchSlots: Monday from 06:00" to monday,
                "batchSlots: one dose alone at a moment is not a batch" to monday.filter { it.reminderId == "med-atorvastatin" },
                "batchSlots: nothing" to emptyList(),
            ).map { (name, rows) ->
                GoldenCase(
                    name,
                    buildJsonObject {
                        put("call", "batchSlots")
                        put("rows", JsonArray(rows.map(::rowJson)))
                    },
                    JsonArray(
                        batchSlots(rows).entries.sortedBy { it.key }.map { (time, slot) ->
                            buildJsonObject {
                                put("scheduledTime", time)
                                put("rows", JsonArray(slot.map(::rowJson)))
                            }
                        },
                    ),
                )
            }
        val text =
            listOf(listOf("Lisinopril", "Atorvastatin"), listOf("b", "a", "C", "B")).map { names ->
                val t = batchText(names)
                GoldenCase(
                    "batchText: ${names.joinToString()}",
                    buildJsonObject {
                        put("call", "batchText")
                        put("names", JsonArray(names.map(::JsonPrimitive)))
                    },
                    buildJsonObject {
                        put("title", t.title)
                        put("body", t.body)
                    },
                )
            }
        val id =
            GoldenCase(
                "batchNotificationId",
                buildJsonObject {
                    put("call", "batchNotificationId")
                    put("scheduledTime", ny(6, 9))
                },
                JsonPrimitive(batchNotificationId(ny(6, 9))),
            )
        val actions =
            GoldenCase(
                "batch actions and their wire names",
                buildJsonObject { put("call", "BatchAction") },
                JsonArray(
                    (BatchAction.entries.map { it.wire } + listOf("snooze", "later", null)).map { wire ->
                        buildJsonObject {
                            put("wire", wire?.let(::JsonPrimitive) ?: JsonNull)
                            put("fromWire", BatchAction.fromWire(wire)?.wire?.let(::JsonPrimitive) ?: JsonNull)
                        }
                    },
                ),
            )
        return slots + text + id + actions
    }

    private fun sweepCases(): List<GoldenCase> =
        listOf(
            "planMissedSweep: Monday noon" to ny(6, 12),
            "planMissedSweep: Monday 10:59, before 09:00's Grace Period ends" to ny(6, 10, 59),
            "planMissedSweep: Monday 11:00, exactly as it ends" to ny(6, 11),
            "planMissedSweep: Tuesday morning" to ny(7, 8),
            "planMissedSweep: two weeks later, past the lookback" to ny(20, 12),
        ).map { (name, now) ->
            GoldenCase(
                name,
                buildJsonObject {
                    put("call", "planMissedSweep")
                    put("reminders", JsonArray(World.reminders))
                    put("instances", JsonArray(World.instances))
                    put("now", now)
                    put("gracePeriodMs", GRACE_PERIOD_MS)
                    put("lookbackMs", SWEEP_LOOKBACK_MS)
                    put("zone", NY)
                },
                JsonArray(planMissedSweep(PlanMissedSweepInput(reminders, instances, now, GRACE_PERIOD_MS, SWEEP_LOOKBACK_MS, ZONE)).map(::verdictJson)),
            )
        }

    private fun snoozeRanOutCases(): List<GoldenCase> {
        val snoozed = instanceDoc("med-vitamin-d", ny(6, 8), "snoozed", kind = "medication", snoozedUntil = ny(6, 8, 30))
        val noUntil = instanceDoc("med-vitamin-d", ny(6, 8), "snoozed", kind = "medication")
        val pending = instanceDoc("med-vitamin-d", ny(6, 8), "pending", kind = "medication")
        return listOf(
            Triple("snoozeRanOut: inside the Grace Period after the snooze", snoozed, ny(6, 10, 29)),
            Triple("snoozeRanOut: exactly at snoozedUntil plus the Grace Period", snoozed, ny(6, 10, 30)),
            Triple("snoozeRanOut: after it", snoozed, ny(6, 11)),
            Triple("snoozeRanOut: a snooze with no until", noUntil, ny(6, 11)),
            Triple("snoozeRanOut: a row that is not snoozed", pending, ny(6, 23)),
        ).map { (name, doc, now) ->
            GoldenCase(
                name,
                buildJsonObject {
                    put("call", "snoozeRanOut")
                    put("instance", doc)
                    put("now", now)
                    put("gracePeriodMs", GRACE_PERIOD_MS)
                },
                JsonPrimitive(snoozeRanOut(instanceFromDoc(doc), now, GRACE_PERIOD_MS)),
            )
        }
    }

    private fun undoCases(): List<GoldenCase> {
        val byId = World.reminders.associateBy { it["localId"]!!.let { e -> (e as JsonPrimitive).content } }
        data class U(val name: String, val status: InstanceStatus, val reminder: String, val time: Long, val otherOpen: Boolean = false)
        val verdicts =
            listOf(
                U("a completed scheduled dose", InstanceStatus.Completed, "med-atorvastatin", ny(6, 9)),
                U("a skipped scheduled dose", InstanceStatus.Skipped, "med-lisinopril", ny(6, 9)),
                U("a snoozed dose", InstanceStatus.Snoozed, "med-vitamin-d", ny(6, 8)),
                U("a missed dose is not an answer", InstanceStatus.Missed, "med-atorvastatin", ny(5, 21)),
                U("a pending row is not an answer", InstanceStatus.Pending, "task-water", ny(5, 18)),
                U("an extra dose is not a scheduled moment", InstanceStatus.Completed, "med-atorvastatin", ny(6, 10, 15)),
                U("an as-needed dose is not a scheduled moment", InstanceStatus.Completed, "med-ibuprofen", ny(6, 10, 15)),
                U("a Backlog completion is not a scheduled moment", InstanceStatus.Completed, "task-backlog", ny(6, 10)),
                U("another open row at the moment", InstanceStatus.Completed, "med-lisinopril", ny(5, 9), otherOpen = true),
                U("an interval task's slot", InstanceStatus.Completed, "task-stretch", ny(5, 23)),
                U("a once task's moment", InstanceStatus.Completed, "task-pharmacy", ny(6, 15)),
            ).map { u ->
                val doc = byId.getValue(u.reminder)
                GoldenCase(
                    "undoVerdict: ${u.name}",
                    buildJsonObject {
                        put("call", "undoVerdict")
                        put("status", u.status.wire)
                        put("reminder", doc)
                        put("scheduledTime", u.time)
                        put("otherOpenAtMoment", u.otherOpen)
                        put("zone", NY)
                    },
                    JsonPrimitive(
                        when (undoVerdict(u.status, reminderFromDoc(doc), u.time, u.otherOpen, ZONE)) {
                            UndoVerdict.Allowed -> "allowed"
                            UndoVerdict.NotAnAnswer -> "notAnAnswer"
                            UndoVerdict.NotScheduled -> "notScheduled"
                            UndoVerdict.MomentOpenAgain -> "momentOpenAgain"
                        },
                    ),
                )
            }
        val scheduled =
            listOf(
                Triple("isScheduledOccurrence: a moment it produces", "med-atorvastatin", ny(6, 21)),
                Triple("isScheduledOccurrence: a minute off", "med-atorvastatin", ny(6, 21, 1)),
                Triple("isScheduledOccurrence: inside a pause", "med-paused", ny(6, 13)),
                Triple("isScheduledOccurrence: before the pause", "med-paused", ny(5, 13)),
            ).map { (name, id, time) ->
                GoldenCase(
                    name,
                    buildJsonObject {
                        put("call", "isScheduledOccurrence")
                        put("reminder", byId.getValue(id))
                        put("scheduledTime", time)
                        put("zone", NY)
                    },
                    JsonPrimitive(isScheduledOccurrence(reminderFromDoc(byId.getValue(id)), time, ZONE)),
                )
            }
        val missed =
            listOf(
                Triple("undoWillBeMissed: inside the Grace Period", ny(6, 9), ny(6, 10, 59)),
                Triple("undoWillBeMissed: exactly at its end", ny(6, 9), ny(6, 11)),
                Triple("undoWillBeMissed: after it", ny(6, 9), ny(6, 12)),
                Triple("undoWillBeMissed: a moment still ahead", ny(6, 21), ny(6, 12)),
            ).map { (name, time, now) ->
                GoldenCase(
                    name,
                    buildJsonObject {
                        put("call", "undoWillBeMissed")
                        put("scheduledTime", time)
                        put("now", now)
                        put("gracePeriodMs", GRACE_PERIOD_MS)
                    },
                    JsonPrimitive(undoWillBeMissed(time, now, GRACE_PERIOD_MS)),
                )
            }
        return verdicts + scheduled + missed
    }

    override fun cases(): List<GoldenCase> =
        timelineCases() + duplicateCases() + taskCases() + handRowCases() + batchCases() + sweepCases() + snoozeRanOutCases() + undoCases()
}
