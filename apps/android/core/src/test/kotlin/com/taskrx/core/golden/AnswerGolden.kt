package com.taskrx.core.golden

import com.taskrx.core.asneeded.AsNeededWait
import com.taskrx.core.asneeded.asNeededOf
import com.taskrx.core.asneeded.asNeededStatus
import com.taskrx.core.asneeded.showsOnToday
import com.taskrx.core.asneeded.takings
import com.taskrx.core.domain.AsNeededTimes
import com.taskrx.core.snooze.DEFAULT_SNOOZE_MINUTES
import com.taskrx.core.snooze.SNOOZE_CHOICES_MINUTES
import com.taskrx.core.snooze.canPickATimeToday
import com.taskrx.core.snooze.rescheduleInstant
import com.taskrx.core.snooze.snoozeMinutesOrDefault
import com.taskrx.core.snooze.snoozeUntil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

private const val NY = "America/New_York"
private const val MIN = 60_000L
private const val HOUR = 60 * MIN

private fun ny(day: Int, hour: Int = 0, minute: Int = 0, month: Int = 7) = at(NY, 2026, month, day, hour, minute)

private fun asNeeded(gap: Int, max: Int?) =
    buildJsonObject {
        put("type", "asNeeded")
        put("minGapMinutes", gap)
        max?.let { put("maxPer24h", it) }
    }

private fun nullable(value: Long?) = value?.let(::JsonPrimitive) ?: JsonNull

/**
 * Answering on the desktop: an as-needed medication's next allowed dose and whether its card is
 * on Today (`asneeded`), and the snooze choices and pick-a-time rule (`snooze`).
 */
object AnswerGolden : GoldenSet {
    override val name = "answer"
    override val about =
        "asNeededStatus/showsOnToday/takings/asNeededOf, and snoozeMinutesOrDefault/snoozeUntil/rescheduleInstant/" +
            "canPickATimeToday with SNOOZE_CHOICES_MINUTES and DEFAULT_SNOOZE_MINUTES"

    private fun asNeededCases(): List<GoldenCase> {
        val now = ny(6, 12)
        data class A(val name: String, val gap: Int, val max: Int?, val takings: List<Long>, val now: Long = ny(6, 12))
        return listOf(
            A("nothing taken yet", 240, 4, emptyList()),
            A("inside the gap", 240, 4, listOf(now - 3 * HOUR)),
            A("the gap ran out half an hour ago", 240, 4, listOf(now - 4 * HOUR - 30 * MIN)),
            A("the gap runs out exactly now", 240, 4, listOf(now - 4 * HOUR)),
            A("the gap ran out over an hour ago", 240, 4, listOf(now - 6 * HOUR)),
            A("at the daily limit, the limit decides", 60, 3, listOf(now - 20 * HOUR, now - 10 * HOUR, now - 2 * HOUR)),
            A("at the limit but the gap is longer", 600, 2, listOf(now - 23 * HOUR, now - 1 * HOUR)),
            A("a taking exactly 24 hours old is out of the window", 60, 2, listOf(now - 24 * HOUR, now - 5 * HOUR)),
            A("over the limit, the oldest extra must leave", 30, 2, listOf(now - 22 * HOUR, now - 21 * HOUR, now - 3 * HOUR)),
            A("the limit holds until the older dose leaves the window", 60, 2, listOf(now - 24 * HOUR - 20 * MIN, now - 23 * HOUR - 50 * MIN, now - 2 * HOUR)),
            A("no daily limit", 120, null, listOf(now - 3 * HOUR, now - 5 * HOUR, now - 7 * HOUR)),
            A("takings in any order", 240, 4, listOf(now - 2 * HOUR, now - 9 * HOUR, now - 5 * HOUR)),
            A("one taking in the future", 240, 4, listOf(now + HOUR)),
        ).map { a ->
            val schedule = AsNeededTimes(a.gap, a.max)
            val status = asNeededStatus(schedule, a.takings, a.now)
            GoldenCase(
                "asNeededStatus: ${a.name}",
                buildJsonObject {
                    put("call", "asNeededStatus")
                    put("schedule", asNeeded(a.gap, a.max))
                    put("takings", JsonArray(a.takings.map(::JsonPrimitive)))
                    put("now", a.now)
                },
                buildJsonObject {
                    put("lastTakenAt", nullable(status.lastTakenAt))
                    put("takenIn24h", status.takenIn24h)
                    put("readyAt", nullable(status.readyAt))
                    put(
                        "wait",
                        when (status.wait) {
                            null -> JsonNull
                            AsNeededWait.Gap -> JsonPrimitive("gap")
                            AsNeededWait.Limit -> JsonPrimitive("limit")
                        },
                    )
                    put("openedAt", nullable(status.openedAt))
                    put("isReady", status.isReady(a.now))
                    put("showsOnToday", showsOnToday(status, a.now))
                },
            )
        }
    }

    private fun takingsCases(): List<GoldenCase> {
        val docs =
            listOf(
                instanceDoc("med-ibuprofen", ny(6, 10), "completed", kind = "medication", completedAt = ny(6, 10, 5)),
                instanceDoc("med-ibuprofen", ny(6, 4), "completed", kind = "medication", completedAt = ny(6, 4)),
                instanceDoc("med-ibuprofen", ny(6, 6), "skipped", kind = "medication"),
                instanceDoc("med-ibuprofen", ny(6, 7), "snoozed", kind = "medication", snoozedUntil = ny(6, 7, 15)),
                instanceDoc("med-ibuprofen", ny(6, 8), "completed", kind = "medication"),
                instanceDoc("med-other", ny(6, 9), "completed", kind = "medication", completedAt = ny(6, 9)),
            )
        val schedules =
            listOf(
                "an as-needed medication" to asNeeded(240, 4),
                "a daily medication" to buildJsonObject {
                    put("type", "daily")
                    put("times", JsonArray(listOf(JsonPrimitive("09:00"))))
                },
            )
        return listOf(
            GoldenCase(
                "takings: completed doses with a completion time, of that reminder only",
                buildJsonObject {
                    put("call", "takings")
                    put("instances", JsonArray(docs))
                    put("reminderId", "med-ibuprofen")
                },
                JsonArray(takings(docs.map(::instanceFromDoc), "med-ibuprofen").map(::JsonPrimitive)),
            ),
        ) +
            schedules.map { (name, schedule) ->
                val doc = reminderDoc(localId = "med-ibuprofen", schedule = schedule)
                GoldenCase(
                    "asNeededOf: $name",
                    buildJsonObject {
                        put("call", "asNeededOf")
                        put("reminder", doc)
                    },
                    asNeededOf(reminderFromDoc(doc))?.let { asNeeded(it.minGapMinutes, it.maxPer24h) } ?: JsonNull,
                )
            }
    }

    private fun snoozeCases(): List<GoldenCase> {
        val constants =
            GoldenCase(
                "snooze choices and default",
                buildJsonObject { put("call", "constants") },
                buildJsonObject {
                    put("choices", JsonArray(SNOOZE_CHOICES_MINUTES.map(::JsonPrimitive)))
                    put("default", DEFAULT_SNOOZE_MINUTES)
                },
            )
        val stored =
            listOf(null, 5, 10, 15, 30, 60, 0, 7, 45, 120, -5).map { value ->
                GoldenCase(
                    "snoozeMinutesOrDefault: ${value ?: "nothing stored"}",
                    buildJsonObject {
                        put("call", "snoozeMinutesOrDefault")
                        put("stored", value?.let(::JsonPrimitive) ?: JsonNull)
                    },
                    JsonPrimitive(snoozeMinutesOrDefault(value)),
                )
            }
        val until =
            listOf(5, 15, 60).map { minutes ->
                GoldenCase(
                    "snoozeUntil: $minutes minutes",
                    buildJsonObject {
                        put("call", "snoozeUntil")
                        put("now", ny(6, 12))
                        put("minutes", minutes)
                    },
                    JsonPrimitive(snoozeUntil(ny(6, 12), minutes)),
                )
            }
        data class P(val name: String, val now: Long, val hour: Int, val minute: Int, val zone: String = NY)
        val pick =
            listOf(
                P("later today", ny(6, 12), 18, 30),
                P("a time already past today", ny(6, 12), 9, 0),
                P("exactly now is not later", ny(6, 12), 12, 0),
                P("a minute from now", ny(6, 12), 12, 1),
                P("hour 24 is refused", ny(6, 12), 24, 0),
                P("minute 60 is refused", ny(6, 12), 13, 60),
                P("a negative hour is refused", ny(6, 12), -1, 0),
                P("a negative minute is refused", ny(6, 12), 13, -1),
                P("23:59 at 23:58", ny(6, 23, 58), 23, 59),
                P("on the spring-forward day, inside the gap", at(NY, 2026, 3, 8, 1), 2, 30),
                P("on the fall-back day, inside the overlap", at(NY, 2026, 11, 1, 0, 30), 1, 30),
                P("the local day is the zone's, not UTC's", at("Australia/Sydney", 2026, 7, 6, 23), 23, 30, "Australia/Sydney"),
            ).map { p ->
                GoldenCase(
                    "rescheduleInstant: ${p.name}",
                    buildJsonObject {
                        put("call", "rescheduleInstant")
                        put("now", p.now)
                        put("hour", p.hour)
                        put("minute", p.minute)
                        put("zone", p.zone)
                    },
                    nullable(rescheduleInstant(p.now, p.hour, p.minute, ZoneId.of(p.zone))),
                )
            }
        val canPick =
            listOf("midday" to ny(6, 12), "23:58" to ny(6, 23, 58), "23:59" to ny(6, 23, 59)).map { (name, now) ->
                GoldenCase(
                    "canPickATimeToday: $name",
                    buildJsonObject {
                        put("call", "canPickATimeToday")
                        put("now", now)
                        put("zone", NY)
                    },
                    JsonPrimitive(canPickATimeToday(now, ZoneId.of(NY))),
                )
            }
        return listOf(constants) + stored + until + pick + canPick
    }

    override fun cases(): List<GoldenCase> = asNeededCases() + takingsCases() + snoozeCases()
}
