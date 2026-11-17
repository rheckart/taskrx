package com.taskrx.core.golden

import com.taskrx.core.domain.instanceIdFor
import com.taskrx.core.schedule.ScheduledOccurrence
import com.taskrx.core.schedule.latestOccurrenceBefore
import com.taskrx.core.schedule.nextOccurrences
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.ZoneId

/** `instanceIdFor` (ADR 0018): the id both devices derive for one Occurrence. */
object InstanceIdGolden : GoldenSet {
    override val name = "instance-id"
    override val about = "instanceIdFor(reminderId, scheduledTime): UUID v5 under the TaskRx namespace"

    override fun cases(): List<GoldenCase> =
        listOf(
            "a phone reminder at 09:00" to ("3f1c0a52-6a0e-4d8e-9a7f-2b1c9e0d4a11" to 1_763_283_600_000L),
            "the same reminder an hour later" to ("3f1c0a52-6a0e-4d8e-9a7f-2b1c9e0d4a11" to 1_763_287_200_000L),
            "a desktop reminder" to ("b2a9d6f0-1c3e-4f5a-8b7c-9d0e1f2a3b4c" to 1_763_283_600_000L),
            "the epoch" to ("3f1c0a52-6a0e-4d8e-9a7f-2b1c9e0d4a11" to 0L),
            "a moment before the epoch" to ("3f1c0a52-6a0e-4d8e-9a7f-2b1c9e0d4a11" to -1L),
            "a non-ASCII id is hashed as UTF-8" to ("local-ü" to 0L),
            "an upper-case id is not folded" to ("3F1C0A52-6A0E-4D8E-9A7F-2B1C9E0D4A11" to 1_763_283_600_000L),
            "the largest moment" to ("r" to Long.MAX_VALUE),
        ).map { (name, args) ->
            val (reminderId, scheduledTime) = args
            GoldenCase(
                name,
                buildJsonObject {
                    put("reminderId", reminderId)
                    put("scheduledTime", scheduledTime)
                },
                JsonPrimitive(instanceIdFor(reminderId, scheduledTime)),
            )
        }
}

// ---- schedule shapes, as the server stores them -------------------------------------------

private fun daily(vararg times: String) =
    buildJsonObject {
        put("type", "daily")
        putJsonArray("times") { times.forEach { add(it) } }
    }

private fun interval(hours: Long, startAt: Long) =
    buildJsonObject {
        put("type", "interval")
        put("hours", hours)
        put("startAt", startAt)
    }

private fun weekly(days: List<Int>, vararg times: String) =
    buildJsonObject {
        put("type", "weekly")
        putJsonArray("daysOfWeek") { days.forEach { add(it) } }
        putJsonArray("times") { times.forEach { add(it) } }
    }

private fun monthly(days: List<Int>, vararg times: String) =
    buildJsonObject {
        put("type", "monthly")
        putJsonArray("daysOfMonth") { days.forEach { add(it) } }
        putJsonArray("times") { times.forEach { add(it) } }
    }

private fun once(at: Long) =
    buildJsonObject {
        put("type", "once")
        put("at", at)
    }

private val none = buildJsonObject { put("type", "none") }

private val asNeeded =
    buildJsonObject {
        put("type", "asNeeded")
        put("minGapMinutes", 240)
        put("maxPer24h", 4)
    }

private fun pauses(vararg windows: Pair<Long, Long?>): JsonArray =
    buildJsonArray {
        for ((from, until) in windows) {
            addJsonObject {
                put("from", from)
                until?.let { put("until", it) }
            }
        }
    }

private fun moves(vararg moves: Pair<Long, Long>): JsonArray =
    buildJsonArray {
        for ((from, to) in moves) {
            addJsonObject {
                put("from", from)
                put("to", to)
            }
        }
    }

private fun occurrence(o: ScheduledOccurrence): JsonObject =
    buildJsonObject {
        put("occurrenceId", o.occurrenceId)
        put("reminderId", o.reminderId)
        put("name", o.name)
        put("dosage", o.dosage?.let(::JsonPrimitive) ?: JsonNull)
        put("fireAt", o.fireAt)
    }

private const val NY = "America/New_York"
private const val UTC = "UTC"
private const val LONDON = "Europe/London"
private const val SYDNEY = "Australia/Sydney"
private const val HOUR = 3_600_000L

/**
 * `nextOccurrences` and `latestOccurrenceBefore`: every Schedule variant, pauses, moved
 * occurrences, monthly clamping and the clock changes of three zones (one southern).
 */
object ScheduleGolden : GoldenSet {
    override val name = "schedule"
    override val about =
        "nextOccurrences(reminder, fromMs, count, zone) and latestOccurrenceBefore(reminder, now, zone). " +
            "`reminder` is a Convex reminders document; a schedule the server shape does not allow reads as none."

    private data class Next(val name: String, val doc: JsonObject, val from: Long, val count: Int, val zone: String)

    private data class Latest(val name: String, val doc: JsonObject, val now: Long, val zone: String)

    private fun next(name: String, schedule: JsonObject, from: Long, count: Int, zone: String = NY, pauses: JsonArray? = null, overrides: JsonArray? = null, kind: String = "medication", active: Boolean = true) =
        Next(name, reminderDoc(schedule = schedule, pauses = pauses, overrides = overrides, kind = kind, dosage = if (kind == "task") null else "20mg", name = if (kind == "task") "Water the plants" else "Atorvastatin", active = active), from, count, zone)

    private fun latest(name: String, schedule: JsonObject, now: Long, zone: String = NY, pauses: JsonArray? = null, overrides: JsonArray? = null) =
        Latest(name, reminderDoc(schedule = schedule, pauses = pauses, overrides = overrides, kind = "task", dosage = null, name = "Water the plants"), now, zone)

    private fun nextCases(): List<Next> {
        val jul3 = at(NY, 2026, 7, 3)
        val startAt = at(NY, 2026, 7, 3, 6)
        val nine = { d: Int -> at(NY, 2026, 7, d, 9) }
        return listOf(
            // daily
            next("daily: two times a day, in order", daily("21:00", "09:00"), jul3, 5),
            next("daily: two times inside one hour order by minute", daily("08:45", "08:15"), jul3, 2),
            next("daily: a slot already past today rolls to tomorrow", daily("09:00"), at(NY, 2026, 7, 3, 10), 1),
            next("daily: a moment exactly at from is excluded", daily("09:00"), nine(3), 1),
            next("daily: no times, no moments", daily(), jul3, 5),
            next("daily: an unreadable time makes the schedule unreadable", daily("9am", "09:00"), jul3, 2),
            next("daily: an hour past 23 is unreadable", daily("24:00"), jul3, 2),
            next("daily: a single-digit hour reads", daily("7:05"), jul3, 2),
            next("daily: stops at the 60-day horizon", daily("09:00"), jul3, 70),
            next("daily: spring forward, 02:30 does not exist", daily("02:30", "09:00"), at(NY, 2026, 3, 7), 6),
            next("daily: fall back, 01:30 happens twice", daily("01:30", "09:00"), at(NY, 2026, 10, 31), 6),
            next("daily: London's spring change", daily("01:30", "09:00"), at(LONDON, 2026, 3, 28), 6, LONDON),
            next("daily: Sydney's autumn change", daily("02:30"), at(SYDNEY, 2026, 4, 4), 3, SYDNEY),
            next("daily: in UTC", daily("09:00"), at(UTC, 2026, 7, 3), 3, UTC),
            next("daily: a task", daily("18:00"), jul3, 2, kind = "task"),
            next("daily: an inactive reminder still has moments", daily("09:00"), jul3, 2, active = false),
            next("count zero is nothing", daily("09:00"), jul3, 0),
            next("a negative count is refused", daily("09:00"), jul3, -1),
            // interval
            next("interval: from before the start begins at the start", interval(8, startAt), startAt - HOUR, 3),
            next("interval: fast-forwards to the slot after from", interval(8, startAt), startAt + 20 * HOUR, 3),
            next("interval: a slot exactly at from is excluded", interval(8, startAt), startAt + 16 * HOUR, 2),
            next("interval: the start exactly at from is excluded", interval(8, startAt), startAt, 2),
            next("interval: zero hours is unreadable", interval(0, startAt), startAt, 3),
            next("interval: crosses fall back by elapsed time, not wall clock", interval(6, at(NY, 2026, 10, 31, 20)), at(NY, 2026, 10, 31, 19), 5),
            next("interval: not capped by a day horizon", interval(24, startAt), startAt, 75),
            // weekly (0 is Sunday)
            next("weekly: only the selected weekdays", weekly(listOf(1, 3, 5), "07:00"), jul3, 5),
            next("weekly: several times on a selected day", weekly(listOf(1), "19:00", "07:00"), at(NY, 2026, 7, 6), 2),
            next("weekly: no days, no moments", weekly(emptyList(), "09:00"), jul3, 5),
            next("weekly: no times, no moments", weekly(listOf(1), *emptyArray()), jul3, 5),
            next("weekly: a day outside 0..6 is unreadable", weekly(listOf(7), "09:00"), jul3, 5),
            next("weekly: a moment exactly at from is excluded", weekly(listOf(1), "07:00"), at(NY, 2026, 7, 6, 7), 1),
            next("weekly: stops at the 56-day horizon", weekly(listOf(0), "09:00"), jul3, 20),
            next("weekly: Sunday across spring forward", weekly(listOf(0), "02:30"), at(NY, 2026, 3, 1), 3),
            // monthly
            next("monthly: the dates it names", monthly(listOf(15), "09:00"), at(NY, 2026, 1, 1), 4),
            next("monthly: the 31st lands on a short month's last day", monthly(listOf(31), "09:00"), at(NY, 2026, 1, 1), 6),
            next("monthly: the 31st lands on February 29 in a leap year", monthly(listOf(31), "09:00"), at(NY, 2028, 2, 1), 1),
            next("monthly: the 30th and 31st collapse onto one February date", monthly(listOf(30, 31), "09:00"), at(NY, 2026, 2, 1), 2),
            next("monthly: the 29th skips a non-leap February", monthly(listOf(29), "09:00"), at(NY, 2026, 2, 1), 1),
            next("monthly: dates in any order come out in order", monthly(listOf(20, 5), "18:00", "08:00"), at(NY, 2026, 1, 1), 4),
            next("monthly: a later date this month comes due this month", monthly(listOf(10), "09:00"), at(NY, 2026, 1, 2), 1),
            next("monthly: a moment exactly at from is excluded", monthly(listOf(15), "09:00"), at(NY, 2026, 1, 15, 9), 1),
            next("monthly: no dates, no moments", monthly(emptyList(), "09:00"), at(NY, 2026, 1, 1), 5),
            next("monthly: a date of 0 is unreadable", monthly(listOf(0), "09:00"), at(NY, 2026, 1, 1), 5),
            next("monthly: keeps its wall-clock time across a clock change", monthly(listOf(1), "09:00"), at(NY, 2026, 2, 15), 2),
            next("monthly: stops at the 400-day horizon", monthly(listOf(1), "09:00"), at(NY, 2026, 1, 1), 20),
            // once, none, as needed
            next("once: comes due exactly once", once(at(NY, 2026, 7, 10, 14)), jul3, 64),
            next("once: spent once its moment has passed", once(at(NY, 2026, 7, 10, 14)), at(NY, 2026, 7, 10, 14, 1), 64),
            next("once: a moment exactly at from is excluded", once(at(NY, 2026, 7, 10, 14)), at(NY, 2026, 7, 10, 14), 64),
            next("none: the Backlog never comes due", none, jul3, 64),
            next("asNeeded: never comes due", asNeeded, jul3, 64),
            // pauses
            next("pause: moments inside a closed pause are skipped, count still filled", daily("09:00"), jul3, 4, pauses = pauses(nine(4) to nine(6))),
            next("pause: the first moment of a pause is paused, the moment it ends is not", daily("09:00"), jul3, 3, pauses = pauses(nine(4) to nine(5))),
            next("pause: an open pause leaves only the moments before it", daily("09:00"), jul3, 5, pauses = pauses(nine(5) to null)),
            next("pause: asking from inside an open pause finds nothing", daily("09:00"), nine(6), 5, pauses = pauses(nine(5) to null)),
            next("pause: asking from inside a closed pause starts at its end", daily("09:00"), nine(5), 2, pauses = pauses(nine(4) to at(NY, 2026, 7, 8, 12))),
            next("pause: longer than the look-ahead still resumes after it", daily("09:00"), jul3, 2, pauses = pauses(nine(4) to at(NY, 2026, 10, 1))),
            next("pause: two pauses are both honoured", daily("09:00"), jul3, 4, pauses = pauses(nine(4) to nine(5), nine(6) to nine(7))),
            next("pause: an interval keeps its grid across a pause", interval(8, startAt), startAt - 1, 4, pauses = pauses(startAt + HOUR to startAt + 17 * HOUR)),
            next("pause: a once moment inside a pause is gone", once(nine(10)), jul3, 2, pauses = pauses(nine(9) to nine(11))),
            // moved occurrences
            next("move: an occurrence comes back at its new moment", daily("09:00"), jul3, 3, overrides = moves(nine(4) to at(NY, 2026, 7, 4, 11, 30))),
            next("move: a source that has passed still moves its occurrence", daily("09:00"), at(NY, 2026, 7, 4, 10), 2, overrides = moves(nine(4) to at(NY, 2026, 7, 4, 15))),
            next("move: a target already behind us changes nothing", daily("09:00"), at(NY, 2026, 7, 4, 10), 2, overrides = moves(nine(4) to at(NY, 2026, 7, 4, 9, 30))),
            next("move: two occurrences moved onto one moment give one", daily("09:00"), jul3, 3, overrides = moves(nine(4) to at(NY, 2026, 7, 5, 12), nine(5) to at(NY, 2026, 7, 5, 12))),
            next("move: a move that reorders the batch is sorted", daily("09:00"), jul3, 4, overrides = moves(nine(4) to at(NY, 2026, 7, 6, 12))),
            next("move: a source the schedule never produces is inert", daily("09:00"), jul3, 2, overrides = moves(at(NY, 2026, 7, 4, 10) to at(NY, 2026, 7, 4, 15))),
            next("move: a pause over the source keeps the occurrence where it was", daily("09:00"), jul3, 3, pauses = pauses(at(NY, 2026, 7, 4, 8) to at(NY, 2026, 7, 4, 10)), overrides = moves(nine(4) to at(NY, 2026, 7, 4, 15))),
            next("move: a pause over the target leaves the occurrence at its own moment", daily("09:00"), jul3, 3, pauses = pauses(at(NY, 2026, 7, 4, 14) to at(NY, 2026, 7, 4, 16)), overrides = moves(nine(4) to at(NY, 2026, 7, 4, 15))),
            next("move: a once reminder moves", once(nine(10)), jul3, 2, overrides = moves(nine(10) to at(NY, 2026, 7, 9, 18))),
            next("move: an interval moves only from a real slot", interval(8, startAt), startAt - 1, 3, overrides = moves(startAt + 8 * HOUR to startAt + 9 * HOUR, startAt + 3 * HOUR to startAt + 4 * HOUR)),
            next("move: the Backlog ignores its moves", none, jul3, 2, overrides = moves(nine(4) to nine(5))),
            next("move: across a clock change both wall-clock times hold", daily("09:00"), at(NY, 2026, 3, 7), 3, overrides = moves(at(NY, 2026, 3, 7, 9) to at(NY, 2026, 3, 8, 8))),
            next("move: the answer is as long as asked for", daily("09:00"), jul3, 3, overrides = moves(nine(4) to at(NY, 2026, 7, 20, 9), nine(5) to at(NY, 2026, 7, 21, 9))),
            next("move: count zero is nothing, moves or not", daily("09:00"), jul3, 0, overrides = moves(nine(4) to nine(5))),
        )
    }

    private fun latestCases(): List<Latest> {
        val jul6 = at(NY, 2026, 7, 6, 12) // a Monday
        return listOf(
            latest("latest: once, its moment while past", once(at(NY, 2026, 7, 1, 9)), jul6),
            latest("latest: once, a future moment has nothing behind it", once(at(NY, 2026, 7, 9, 9)), jul6),
            latest("latest: a moment exactly now is not before now", daily("12:00"), jul6),
            latest("latest: daily, the last time passed today", daily("08:00", "11:00", "18:00"), jul6),
            latest("latest: daily, yesterday when today's times are ahead", daily("18:00", "20:00"), jul6),
            latest("latest: weekly, back to the last selected weekday", weekly(listOf(4), "09:00"), jul6),
            latest("latest: weekly, several days and times", weekly(listOf(5, 1), "07:00", "13:00"), jul6),
            latest("latest: monthly, several dates and times", monthly(listOf(1, 3), "07:00", "20:00"), jul6),
            latest("latest: monthly, back to the last selected date", monthly(listOf(20), "09:00"), jul6),
            latest("latest: monthly, a 31st clamps to a short month's last day", monthly(listOf(31), "09:00"), at(NY, 2026, 7, 2)),
            latest("latest: interval, the slot just before now", interval(5, at(NY, 2026, 7, 1)), jul6),
            latest("latest: interval of zero hours has none", interval(0, at(NY, 2026, 7, 1)), jul6),
            latest("latest: the Backlog has none", none, jul6),
            latest("latest: as needed has none", asNeeded, jul6),
            latest("latest: a paused moment is not a moment", daily("09:00"), jul6, pauses = pauses(at(NY, 2026, 7, 6, 8) to at(NY, 2026, 7, 6, 10))),
            latest("latest: a pause longer than a cycle leaves nothing", daily("09:00"), jul6, pauses = pauses(at(NY, 2026, 7, 1) to at(NY, 2026, 7, 6, 11))),
            latest("latest: a moved moment is found where it moved to", daily("09:00"), jul6, overrides = moves(at(NY, 2026, 7, 6, 9) to at(NY, 2026, 7, 6, 11))),
            latest("latest: across fall back", daily("01:30"), at(NY, 2026, 11, 1, 3)),
            latest("latest: in London", daily("09:00"), at(LONDON, 2026, 7, 6, 12), LONDON),
        )
    }

    override fun cases(): List<GoldenCase> =
        nextCases().map { c ->
            GoldenCase(
                c.name,
                buildJsonObject {
                    put("call", "nextOccurrences")
                    put("reminder", c.doc)
                    put("fromMs", c.from)
                    put("count", c.count)
                    put("zone", c.zone)
                },
                outcome {
                    JsonArray(nextOccurrences(reminderFromDoc(c.doc), c.from, c.count, ZoneId.of(c.zone)).map(::occurrence))
                },
            )
        } +
            latestCases().map { c ->
                GoldenCase(
                    c.name,
                    buildJsonObject {
                        put("call", "latestOccurrenceBefore")
                        put("reminder", c.doc)
                        put("now", c.now)
                        put("zone", c.zone)
                    },
                    latestOccurrenceBefore(reminderFromDoc(c.doc), c.now, ZoneId.of(c.zone))?.let(::occurrence) ?: JsonNull,
                )
            }
}
