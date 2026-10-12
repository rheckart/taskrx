package com.taskrx.core.sync

import com.taskrx.core.domain.DailyTimes
import com.taskrx.core.domain.IntervalTimes
import com.taskrx.core.domain.NoneTimes
import com.taskrx.core.domain.OnceTimes
import com.taskrx.core.domain.ScheduleTimes
import com.taskrx.core.domain.WeeklyTimes
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The schedule as Convex stores it (`scheduleTimesValidator` in packages/convex/convex/schema.ts),
 * read into the domain union. The inverse of what the app writes into a queue payload.
 *
 * Every number arrives as a float64: whole values are spelled `1700000000000.0` or
 * `1.7E12` as readily as `1700000000000`, so nothing here asks for an integer token.
 */

private val TIME = Regex("""([0-9]{1,2}):([0-9]{2})""")

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number with no fractional part, as a Long; null for anything else (NaN and infinity fail the modulo test). */
private fun JsonElement?.whole(): Long? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    val number = primitive.content.toDoubleOrNull() ?: return null
    return if (number % 1.0 == 0.0) number.toLong() else null
}

private fun times(value: JsonElement?): List<String>? {
    if (value !is JsonArray) return null
    return value.map { element ->
        val match = TIME.matchEntire(element.text() ?: return null) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        "%02d:%02d".format(Locale.ROOT, hour, minute)
    }
}

private fun weekdays(value: JsonElement?): List<Int>? {
    if (value !is JsonArray) return null
    return value.map { element ->
        val day = element.whole() ?: return null
        if (day !in 0..6) return null
        day.toInt()
    }
}

/**
 * Reads a server schedule, or returns null for anything malformed. The caller decides what
 * null means; the pull turns it into a Backlog item, because a schedule that cannot be read
 * must not be passed to the Occurrence engine, and the Backlog is where it can be repaired.
 */
fun scheduleFromServer(value: JsonElement?): ScheduleTimes? {
    if (value !is JsonObject) return null
    return when (value["type"].text()) {
        "daily" -> times(value["times"])?.let(::DailyTimes)
        "interval" -> {
            val hours = value["hours"].whole()
            val startAt = value["startAt"].whole()
            if (hours == null || startAt == null || hours <= 0 || hours > Int.MAX_VALUE) {
                null
            } else {
                IntervalTimes(hours.toInt(), startAt)
            }
        }
        "weekly" -> {
            val days = weekdays(value["daysOfWeek"])
            val at = times(value["times"])
            if (days == null || at == null) null else WeeklyTimes(days, at)
        }
        "once" -> value["at"].whole()?.let(::OnceTimes)
        "none" -> NoneTimes
        else -> null
    }
}
