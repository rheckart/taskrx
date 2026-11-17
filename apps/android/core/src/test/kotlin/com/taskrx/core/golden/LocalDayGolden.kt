package com.taskrx.core.golden

import com.taskrx.core.time.atClockTimeOnDayOf
import com.taskrx.core.time.localCalendarDaysBetween
import com.taskrx.core.time.localDayBounds
import com.taskrx.core.time.takenAtOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId

/**
 * The local day (`time`): every screen's "today", the Tasks tab's week and Catch up's days are
 * cut with these. The cases sit on the clock changes, where a day is 23 or 25 hours long.
 */
object LocalDayGolden : GoldenSet {
    override val name = "local-day"
    override val about =
        "localDayBounds(now, offsetDays, zone), localCalendarDaysBetween(from, to, zone), " +
            "atClockTimeOnDayOf(day, hour, minute, zone), takenAtOrNull(scheduledTime, hour, minute, now, zone)"

    private data class Moment(val label: String, val zone: String, val ms: Long)

    private val moments =
        listOf(
            Moment("New York, the day before spring forward", "America/New_York", at("America/New_York", 2026, 3, 7, 12)),
            Moment("New York, spring forward (23 hours)", "America/New_York", at("America/New_York", 2026, 3, 8, 12)),
            Moment("New York, fall back (25 hours)", "America/New_York", at("America/New_York", 2026, 11, 1, 12)),
            Moment("New York, 00:30 on fall back", "America/New_York", at("America/New_York", 2026, 11, 1, 0, 30)),
            Moment("London, spring forward", "Europe/London", at("Europe/London", 2026, 3, 29, 12)),
            Moment("London, fall back", "Europe/London", at("Europe/London", 2026, 10, 25, 12)),
            Moment("Sydney, fall back", "Australia/Sydney", at("Australia/Sydney", 2026, 4, 5, 12)),
            Moment("Sydney, spring forward", "Australia/Sydney", at("Australia/Sydney", 2026, 10, 4, 12)),
            Moment("UTC", "UTC", at("UTC", 2026, 7, 6, 23, 59)),
        )

    override fun cases(): List<GoldenCase> {
        val bounds =
            moments.flatMap { m ->
                listOf(-1, 0, 1, 7).map { offset ->
                    val b = localDayBounds(m.ms, offset, ZoneId.of(m.zone))
                    GoldenCase(
                        "localDayBounds: ${m.label}, offset $offset",
                        buildJsonObject {
                            put("call", "localDayBounds")
                            put("now", m.ms)
                            put("offsetDays", offset)
                            put("zone", m.zone)
                        },
                        buildJsonObject {
                            put("start", b.start)
                            put("end", b.end)
                        },
                    )
                }
            }
        val ny = "America/New_York"
        val between =
            listOf(
                Triple("across spring forward", at(ny, 2026, 3, 7, 23), at(ny, 2026, 3, 9, 1)),
                Triple("across fall back", at(ny, 2026, 10, 31, 23), at(ny, 2026, 11, 2, 1)),
                Triple("the same day", at(ny, 2026, 11, 1, 0, 30), at(ny, 2026, 11, 1, 23)),
                Triple("backwards", at(ny, 2026, 11, 5, 9), at(ny, 2026, 11, 1, 9)),
                Triple("a year", at(ny, 2026, 1, 1, 9), at(ny, 2027, 1, 1, 9)),
            ).map { (name, from, to) ->
                GoldenCase(
                    "localCalendarDaysBetween: $name",
                    buildJsonObject {
                        put("call", "localCalendarDaysBetween")
                        put("from", from)
                        put("to", to)
                        put("zone", ny)
                    },
                    JsonPrimitive(localCalendarDaysBetween(from, to, ZoneId.of(ny))),
                )
            }
        val clock =
            listOf(
                Triple("02:30 on spring forward does not exist", at(ny, 2026, 3, 8, 12), 2 to 30),
                Triple("01:30 on fall back happens twice", at(ny, 2026, 11, 1, 12), 1 to 30),
                Triple("an ordinary morning", at(ny, 2026, 7, 6, 12), 8 to 15),
            ).map { (name, day, hm) ->
                GoldenCase(
                    "atClockTimeOnDayOf: $name",
                    buildJsonObject {
                        put("call", "atClockTimeOnDayOf")
                        put("day", day)
                        put("hour", hm.first)
                        put("minute", hm.second)
                        put("zone", ny)
                    },
                    JsonPrimitive(atClockTimeOnDayOf(day, hm.first, hm.second, ZoneId.of(ny))),
                )
            }
        val taken =
            listOf(
                Triple("a time before now", at(ny, 2026, 7, 6, 9) to at(ny, 2026, 7, 6, 12), 8 to 30),
                Triple("a time after now is refused", at(ny, 2026, 7, 6, 9) to at(ny, 2026, 7, 6, 12), 13 to 0),
                Triple("exactly now", at(ny, 2026, 7, 6, 9) to at(ny, 2026, 7, 6, 12), 12 to 0),
            ).map { (name, times, hm) ->
                val (scheduled, now) = times
                GoldenCase(
                    "takenAtOrNull: $name",
                    buildJsonObject {
                        put("call", "takenAtOrNull")
                        put("scheduledTime", scheduled)
                        put("hour", hm.first)
                        put("minute", hm.second)
                        put("now", now)
                        put("zone", ny)
                    },
                    takenAtOrNull(scheduled, hm.first, hm.second, now, ZoneId.of(ny))?.let(::JsonPrimitive) ?: JsonNull,
                )
            }
        return bounds + between + clock + taken
    }
}
