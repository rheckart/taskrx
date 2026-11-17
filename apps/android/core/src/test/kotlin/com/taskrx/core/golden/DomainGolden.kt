package com.taskrx.core.golden

import com.taskrx.core.domain.InstanceAction
import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.MoveRefusal
import com.taskrx.core.domain.OccurrenceOverride
import com.taskrx.core.domain.ReminderInstance
import com.taskrx.core.domain.ReminderKind
import com.taskrx.core.domain.ReminderPhase
import com.taskrx.core.domain.actionTarget
import com.taskrx.core.domain.addPause
import com.taskrx.core.domain.allowsInstanceTransition
import com.taskrx.core.domain.availableActions
import com.taskrx.core.domain.canPause
import com.taskrx.core.domain.isPausedAt
import com.taskrx.core.domain.livePause
import com.taskrx.core.domain.moveRefusal
import com.taskrx.core.domain.overridesFromJson
import com.taskrx.core.domain.overridesToJson
import com.taskrx.core.domain.pausesFromJson
import com.taskrx.core.domain.pausesToJson
import com.taskrx.core.domain.reminderActions
import com.taskrx.core.domain.reminderPhase
import com.taskrx.core.domain.resumePause
import com.taskrx.core.domain.withOverride
import com.taskrx.core.domain.withoutOverride
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val NY = "America/New_York"
private const val HOUR = 3_600_000L

private fun pauseArray(vararg windows: Pair<Long, Long?>): JsonArray =
    buildJsonArray {
        for ((from, until) in windows) {
            addJsonObject {
                put("from", from)
                until?.let { put("until", it) }
            }
        }
    }

private fun moveArray(vararg moves: Triple<Long, Long, String?>): JsonArray =
    buildJsonArray {
        for ((from, to, notice) in moves) {
            addJsonObject {
                put("from", from)
                put("to", to)
                notice?.let { put("noticeId", it) }
            }
        }
    }

private fun daily(vararg times: String) =
    buildJsonObject {
        put("type", "daily")
        put("times", JsonArray(times.map(::JsonPrimitive)))
    }

private val asNeeded =
    buildJsonObject {
        put("type", "asNeeded")
        put("minGapMinutes", 240)
    }

private fun strings(values: Iterable<String>) = JsonArray(values.sorted().map(::JsonPrimitive))

/**
 * A Convex `reminderInstances` document for [reminderLocalId]'s moment at [scheduledTime].
 * The server's `reminderId` is a document id; the desktop resolves it to the parent's
 * `localId`, as the phone's pull does, so the rules here see the local id.
 */
fun instanceDoc(
    reminderLocalId: String,
    scheduledTime: Long,
    status: String,
    kind: String = "task",
    completedAt: Long? = null,
    snoozedUntil: Long? = null,
    localId: String = "inst-$scheduledTime-$status",
): JsonObject =
    buildJsonObject {
        put("_id", "j57inst$scheduledTime")
        put("localId", localId)
        put("userId", "user_golden")
        put("reminderId", reminderLocalId)
        put("reminderKind", kind)
        put("scheduledTime", scheduledTime)
        put("status", status)
        completedAt?.let { put("completedAt", it) }
        snoozedUntil?.let { put("snoozedUntil", it) }
        put("snoozeCount", 0)
        put("createdAt", scheduledTime)
        put("updatedAt", scheduledTime)
    }

/** The phone's `ServerDocs.toPulled` for an instance, with `reminderId` already the parent's local id. */
fun instanceFromDoc(doc: JsonObject): ReminderInstance {
    fun text(key: String) = (doc[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun long(key: String) = (doc[key] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toDouble()?.toLong()
    return ReminderInstance(
        id = text("localId")!!,
        reminderId = text("reminderId")!!,
        userId = text("userId")!!,
        reminderKind = ReminderKind.fromWire(text("reminderKind")!!)!!,
        scheduledTime = long("scheduledTime")!!,
        status = InstanceStatus.fromWire(text("status")!!)!!,
        completedAt = long("completedAt"),
        snoozedUntil = long("snoozedUntil"),
        snoozeCount = long("snoozeCount")!!.toInt(),
        notes = text("notes"),
        createdAt = long("createdAt")!!,
        updatedAt = long("updatedAt")!!,
    )
}

private fun phaseJson(phase: ReminderPhase): JsonElement =
    when (phase) {
        ReminderPhase.Active -> buildJsonObject { put("phase", "active") }
        ReminderPhase.Stopped -> buildJsonObject { put("phase", "stopped") }
        is ReminderPhase.Paused ->
            buildJsonObject {
                put("phase", "paused")
                put("from", phase.window.from)
                put("until", phase.window.until?.let(::JsonPrimitive) ?: JsonNull)
            }
    }

private fun refusalWire(refusal: MoveRefusal?): JsonElement =
    when (refusal) {
        null -> JsonNull
        MoveRefusal.NotATask -> JsonPrimitive("notATask")
        MoveRefusal.NotAfter -> JsonPrimitive("notAfter")
        MoveRefusal.InThePast -> JsonPrimitive("inThePast")
        MoveRefusal.TooLate -> JsonPrimitive("tooLate")
        MoveRefusal.AlreadyAnswered -> JsonPrimitive("alreadyAnswered")
        MoveRefusal.InProgress -> JsonPrimitive("inProgress")
        MoveRefusal.InAPause -> JsonPrimitive("inAPause")
    }

/**
 * The domain rules every screen leans on: what an answer may do (`InstanceLifecycle`), what a
 * Reminder may do (`ReminderLifecycle`), pauses, and moving a task's occurrence.
 * Sets come out sorted by their wire names; a `ReminderAction` is its lower-case name.
 */
object DomainGolden : GoldenSet {
    override val name = "domain"
    override val about =
        "InstanceLifecycle (availableActions, actionTarget, allowsInstanceTransition), ReminderLifecycle " +
            "(reminderPhase, canPause, reminderActions), pauses (isPausedAt, livePause, addPause, resumePause, " +
            "the pauses field) and moves (moveRefusal, withOverride, withoutOverride, the overrides field)"

    private fun case(name: String, input: JsonObject, output: JsonElement) = GoldenCase(name, input, output)

    private fun instanceLifecycle(): List<GoldenCase> {
        val statuses = InstanceStatus.entries
        val actions =
            statuses.map { status ->
                case(
                    "availableActions: ${status.wire}",
                    buildJsonObject {
                        put("call", "availableActions")
                        put("status", status.wire)
                    },
                    strings(availableActions(status).map { it.wire }),
                )
            }
        val targets =
            InstanceAction.entries.flatMap { action ->
                statuses.map { from ->
                    case(
                        "actionTarget: ${action.wire} from ${from.wire}",
                        buildJsonObject {
                            put("call", "actionTarget")
                            put("action", action.wire)
                            put("from", from.wire)
                        },
                        actionTarget(action, from)?.let { JsonPrimitive(it.wire) } ?: JsonNull,
                    )
                }
            }
        val transitions =
            (listOf<InstanceStatus?>(null) + statuses).flatMap { from ->
                statuses.map { to ->
                    case(
                        "allowsInstanceTransition: ${from?.wire ?: "nothing"} to ${to.wire}",
                        buildJsonObject {
                            put("call", "allowsInstanceTransition")
                            put("from", from?.wire?.let(::JsonPrimitive) ?: JsonNull)
                            put("to", to.wire)
                        },
                        JsonPrimitive(allowsInstanceTransition(from, to)),
                    )
                }
            }
        return actions + targets + transitions
    }

    private fun reminderLifecycle(): List<GoldenCase> {
        val now = at(NY, 2026, 7, 6, 12)
        data class R(val name: String, val doc: JsonObject)
        val reminders =
            listOf(
                R("an active daily medication", reminderDoc(schedule = daily("09:00"))),
                R("a stopped medication", reminderDoc(schedule = daily("09:00"), active = false)),
                R("a stopped medication that was paused", reminderDoc(schedule = daily("09:00"), active = false, pauses = pauseArray(now - HOUR to null))),
                R("a medication paused until tomorrow", reminderDoc(schedule = daily("09:00"), pauses = pauseArray(now - HOUR to now + 24 * HOUR))),
                R("a medication paused with no end", reminderDoc(schedule = daily("09:00"), pauses = pauseArray(now - HOUR to null))),
                R("a medication with a pause that starts tomorrow", reminderDoc(schedule = daily("09:00"), pauses = pauseArray(now + 24 * HOUR to now + 48 * HOUR))),
                R("a medication whose pause has ended", reminderDoc(schedule = daily("09:00"), pauses = pauseArray(now - 48 * HOUR to now - 24 * HOUR))),
                R("a pause that ends exactly now has ended", reminderDoc(schedule = daily("09:00"), pauses = pauseArray(now - HOUR to now))),
                R("an as-needed medication", reminderDoc(schedule = asNeeded)),
                R("a task", reminderDoc(schedule = daily("18:00"), kind = "task", dosage = null, name = "Water the plants")),
                R("a paused task", reminderDoc(schedule = daily("18:00"), kind = "task", dosage = null, name = "Water the plants", pauses = pauseArray(now - HOUR to null))),
            )
        return reminders.map { r ->
            val reminder = reminderFromDoc(r.doc)
            val phase = reminderPhase(reminder, now)
            case(
                "reminder: ${r.name}",
                buildJsonObject {
                    put("call", "reminderLifecycle")
                    put("reminder", r.doc)
                    put("now", now)
                },
                buildJsonObject {
                    put("phase", phaseJson(phase))
                    put("canPause", canPause(reminder))
                    put("actions", strings(reminderActions(reminder, now).map { it.name.lowercase() }))
                },
            )
        }
    }

    private fun pauses(): List<GoldenCase> {
        val t = at(NY, 2026, 7, 6, 12)
        val closed = pauseArray(t to t + 2 * HOUR)
        val open = pauseArray(t to null)
        val two = pauseArray(t - 48 * HOUR to t - 24 * HOUR, t + HOUR to null)
        val pausedAt =
            listOf(
                Triple("before a closed pause", closed, t - 1),
                Triple("at a closed pause's start", closed, t),
                Triple("inside a closed pause", closed, t + HOUR),
                Triple("at a closed pause's end", closed, t + 2 * HOUR),
                Triple("long after an open pause starts", open, t + 1000 * HOUR),
                Triple("with no pauses", JsonArray(emptyList()), t),
            ).map { (name, windows, instant) ->
                case(
                    "isPausedAt: $name",
                    buildJsonObject {
                        put("call", "isPausedAt")
                        put("pauses", windows)
                        put("instant", instant)
                    },
                    JsonPrimitive(isPausedAt(pausesFromJson(windows), instant)),
                )
            }
        val live =
            listOf(
                Triple("a closed pause running now", closed, t + HOUR),
                Triple("a closed pause not started yet", closed, t - HOUR),
                Triple("a closed pause already over", closed, t + 3 * HOUR),
                Triple("an open pause", open, t + 5 * HOUR),
                Triple("the first live of two", two, t),
                Triple("none", JsonArray(emptyList()), t),
            ).map { (name, windows, now) ->
                case(
                    "livePause: $name",
                    buildJsonObject {
                        put("call", "livePause")
                        put("pauses", windows)
                        put("now", now)
                    },
                    livePause(pausesFromJson(windows), now)?.let { pausesToJson(listOf(it)).first() } ?: JsonNull,
                )
            }
        val add =
            listOf(
                Triple("a first pause, open", JsonArray(emptyList()), Triple(t, null as Long?, t)),
                Triple("a pause starting later, closed", JsonArray(emptyList()), Triple(t + HOUR, t + 5 * HOUR, t)),
                Triple("after an old pause has ended", pauseArray(t - 48 * HOUR to t - 24 * HOUR), Triple(t, null, t)),
                Triple("while already paused is refused", open, Triple(t + HOUR, null, t + HOUR)),
                Triple("while a pause is scheduled is refused", pauseArray(t + HOUR to t + 2 * HOUR), Triple(t + 3 * HOUR, null, t)),
                Triple("an end before its start is refused", JsonArray(emptyList()), Triple(t, t, t)),
            ).map { (name, windows, args) ->
                val (from, until, now) = args
                case(
                    "addPause: $name",
                    buildJsonObject {
                        put("call", "addPause")
                        put("pauses", windows)
                        put("from", from)
                        put("until", until?.let(::JsonPrimitive) ?: JsonNull)
                        put("now", now)
                    },
                    outcome { pausesToJson(addPause(pausesFromJson(windows), from, until, now)) },
                )
            }
        val resume =
            listOf(
                Triple("ends a running open pause now", open, t + HOUR),
                Triple("ends a running closed pause now", closed, t + HOUR),
                Triple("cancels a pause that has not started", closed, t - HOUR),
                Triple("cancels a pause starting exactly now", closed, t),
                Triple("with nothing live changes nothing", pauseArray(t - 48 * HOUR to t - 24 * HOUR), t),
                Triple("keeps the history before the live one", two, t + 2 * HOUR),
            ).map { (name, windows, now) ->
                case(
                    "resumePause: $name",
                    buildJsonObject {
                        put("call", "resumePause")
                        put("pauses", windows)
                        put("now", now)
                    },
                    pausesToJson(resumePause(pausesFromJson(windows), now)),
                )
            }
        val field =
            listOf(
                "a closed and an open window" to """[{"from":1,"until":2},{"from":3}]""",
                "float64 spellings" to """[{"from":1.0,"until":2E0}]""",
                "an end equal to the start drops every window" to """[{"from":1,"until":5},{"from":2,"until":2}]""",
                "a fractional moment drops every window" to """[{"from":1.5}]""",
                "a string moment drops every window" to """[{"from":"1"}]""",
                "a null end drops every window" to """[{"from":1,"until":null}]""",
                "a window that is not an object" to """[1]""",
                "missing from" to """[{"until":2}]""",
                "not an array" to """{"from":1}""",
                "absent" to """null""",
            ).map { (name, text) ->
                val raw = Json.parseToJsonElement(text)
                case(
                    "pauses field: $name",
                    buildJsonObject {
                        put("call", "pausesFromJson")
                        put("pauses", raw)
                    },
                    pausesToJson(pausesFromJson(raw)),
                )
            }
        return pausedAt + live + add + resume + field
    }

    private fun moves(): List<GoldenCase> {
        val now = at(NY, 2026, 7, 6, 12)
        val src = at(NY, 2026, 7, 6, 11)
        val task = reminderDoc(schedule = daily("11:00"), kind = "task", dosage = null, name = "Water the plants")
        val taskId = "3f1c0a52-6a0e-4d8e-9a7f-2b1c9e0d4a11"
        val med = reminderDoc(schedule = daily("11:00"))
        val pausedLater = reminderDoc(schedule = daily("11:00"), kind = "task", dosage = null, name = "Water the plants", pauses = pauseArray(now + 2 * HOUR to now + 4 * HOUR))
        val pausedOver = reminderDoc(schedule = daily("11:00"), kind = "task", dosage = null, name = "Water the plants", pauses = pauseArray(src - HOUR to src + HOUR))
        data class M(val name: String, val reminder: JsonObject, val from: Long, val to: Long, val instances: List<JsonObject> = emptyList())
        val refusals =
            listOf(
                M("a task moved later today", task, src, now + 3 * HOUR),
                M("a medication is not a task", med, src, now + 3 * HOUR),
                M("a source past its Grace Period is too late", task, now - 2 * HOUR - 1, now + HOUR),
                M("a source exactly at its Grace Period may still move", task, now - 2 * HOUR, now + HOUR),
                M("an answered source", task, src, now + HOUR, listOf(instanceDoc(taskId, src, "completed", completedAt = src))),
                M("a skipped source", task, src, now + HOUR, listOf(instanceDoc(taskId, src, "skipped"))),
                M("a missed source", task, src, now + HOUR, listOf(instanceDoc(taskId, src, "missed"))),
                M("a pending source is in progress", task, src, now + HOUR, listOf(instanceDoc(taskId, src, "pending"))),
                M("a snoozed source is in progress", task, src, now + HOUR, listOf(instanceDoc(taskId, src, "snoozed", snoozedUntil = now))),
                M("an answer beats an open row", task, src, now + HOUR, listOf(instanceDoc(taskId, src, "pending", localId = "a"), instanceDoc(taskId, src, "skipped", localId = "b"))),
                M("a target not after the source", task, src, src),
                M("a target already past", task, src, now),
                M("a target inside a pause", pausedLater, src, now + 3 * HOUR),
                M("a source inside a pause", pausedOver, src, now + 3 * HOUR),
                M("a future source moved further", task, now + 21 * HOUR, now + 30 * HOUR),
            ).map { m ->
                case(
                    "moveRefusal: ${m.name}",
                    buildJsonObject {
                        put("call", "moveRefusal")
                        put("reminder", m.reminder)
                        put("from", m.from)
                        put("to", m.to)
                        put("now", now)
                        put("instances", JsonArray(m.instances))
                    },
                    refusalWire(moveRefusal(reminderFromDoc(m.reminder), m.from, m.to, now, m.instances.map(::instanceFromDoc))),
                )
            }
        val existing = listOf(OccurrenceOverride(10, 20), OccurrenceOverride(30, 40, "notice-1"))
        val lists =
            listOf(
                Triple("withOverride: adds and sorts by source", "withOverride", OccurrenceOverride(5, 8)),
                Triple("withOverride: replaces a move of the same moment", "withOverride", OccurrenceOverride(30, 45)),
                Triple("withOverride: keeps a notice id", "withOverride", OccurrenceOverride(50, 60, "notice-2")),
            ).map { (name, call, o) ->
                case(
                    name,
                    buildJsonObject {
                        put("call", call)
                        put("overrides", overridesToJson(existing))
                        put("override", overridesToJson(listOf(o)).first())
                    },
                    overridesToJson(existing.withOverride(o)),
                )
            } +
                listOf(30L, 99L).map { from ->
                    case(
                        "withoutOverride: from $from",
                        buildJsonObject {
                            put("call", "withoutOverride")
                            put("overrides", overridesToJson(existing))
                            put("from", from)
                        },
                        overridesToJson(existing.withoutOverride(from)),
                    )
                }
        val field =
            listOf(
                "moves with and without a notice" to """[{"from":1,"to":2},{"from":3,"to":4,"noticeId":"n"}]""",
                "float64 spellings" to """[{"from":1.0,"to":2E0}]""",
                "a move backwards drops every move" to """[{"from":1,"to":2},{"from":5,"to":4}]""",
                "a move to the same moment drops every move" to """[{"from":5,"to":5}]""",
                "a numeric notice id drops every move" to """[{"from":1,"to":2,"noticeId":7}]""",
                "a null notice id drops every move" to """[{"from":1,"to":2,"noticeId":null}]""",
                "missing to" to """[{"from":1}]""",
                "missing from" to """[{"to":2}]""",
                "an object for a notice id drops every move" to """[{"from":1,"to":2,"noticeId":{}}]""",
                "a move that is not an object" to """["1-2"]""",
                "not an array" to """{"from":1,"to":2}""",
                "absent" to """null""",
            ).map { (name, text) ->
                val raw = Json.parseToJsonElement(text)
                case(
                    "overrides field: $name",
                    buildJsonObject {
                        put("call", "overridesFromJson")
                        put("overrides", raw)
                    },
                    overridesToJson(overridesFromJson(raw)),
                )
            }
        return refusals + lists + field
    }

    override fun cases(): List<GoldenCase> = instanceLifecycle() + reminderLifecycle() + pauses() + moves()
}
