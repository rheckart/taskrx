package com.taskrx.core.domain

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * `ScheduleTimes` — the polymorphic schedule shape, ported from
 * `TaskRx.Core/ScheduleTimes.cs` on the `archive/dotnet-maui` branch.
 *
 * The C# version models this as an abstract record with three sealed
 * subrecords, and reconstructs the runtime type on deserialization through
 * `ScheduleTimesJsonConverter`'s `$type` discriminator. Here it is a sealed
 * interface: `when` over it is exhaustively checked by the compiler, so an
 * unhandled variant is a build error rather than a silent runtime fallback.
 *
 * Two representations are deliberately kept apart:
 *
 * - the **domain** shape ([ScheduleTimes]), which uses the conventions the rest
 *   of this package already uses — `"HH:MM"` local wall-clock times and
 *   Unix-millisecond instants;
 * - the **wire** shape (the [JsonObject] [serializeScheduleTimes] writes and
 *   [parseScheduleTimes] reads), which mirrors what
 *   `ScheduleTimesJsonConverter.Write` emits: a `$type` discriminator plus
 *   PascalCase members. It is the same JSON the TypeScript app and Convex
 *   store, so a row written by one is readable by the other.
 *
 * `Weekly` is the capability the POC's medication-only types do not have.
 */

/** `DailyTimes(IReadOnlyList<TimeOnly> Times)` — fixed local times every day. */
data class DailyTimes(
    /** Local wall-clock times as `"HH:MM"`. */
    val times: List<String>,
) : ScheduleTimes

/** `IntervalTimes(int Hours, DateTimeOffset StartAtUtc)` — every N hours. */
data class IntervalTimes(
    val hours: Int,
    /** Unix ms of the first occurrence (C# `StartAtUtc`). */
    val startAt: Long,
) : ScheduleTimes

/** `WeeklyTimes(IReadOnlyList<DayOfWeek> DaysOfWeek, IReadOnlyList<TimeOnly> Times)`. */
data class WeeklyTimes(
    /** Day indices, Sunday = 0 … Saturday = 6, as in C#'s `DayOfWeek` and JS `Date.getDay()`. */
    val daysOfWeek: List<Int>,
    /** Local wall-clock times as `"HH:MM"`, applied on each selected day. */
    val times: List<String>,
) : ScheduleTimes

/**
 * A single moment, and then never again.
 *
 * [at] is an instant, never a date with the time left off — see ADR 0006, which
 * records why an all-day Reminder is a future variant of its own rather than a
 * nullable field here.
 *
 * It has no C# counterpart: `ScheduleTimes.cs` had three subrecords and the
 * archive is frozen, so this variant and [NoneTimes] are the first parts of the
 * union this repository owns outright.
 */
data class OnceTimes(
    /** Unix ms of the one moment this Reminder comes due. */
    val at: Long,
) : ScheduleTimes

/**
 * No schedule at all — the Backlog.
 *
 * A Reminder with this schedule produces no Occurrences and never appears on
 * the timeline; it is rendered from the Reminder row itself, and completing one
 * mints its Instance at the moment the person acts (ADR 0002).
 *
 * It replaces something that used to happen by accident. A `daily` schedule
 * with no times also produces nothing, which looks like a Backlog item but is
 * really a misconfiguration; after this variant the difference is expressible in
 * the type, and [createReminder]'s default of an empty `daily` schedule is the
 * one remaining place the two are still conflated.
 */
data object NoneTimes : ScheduleTimes

sealed interface ScheduleTimes

fun dailyTimes(times: List<String>): DailyTimes = DailyTimes(times.toList())

fun intervalTimes(hours: Int, startAt: Long): IntervalTimes = IntervalTimes(hours, startAt)

fun weeklyTimes(daysOfWeek: List<Int>, times: List<String>): WeeklyTimes =
    WeeklyTimes(daysOfWeek.toList(), times.toList())

fun onceTimes(at: Long): OnceTimes = OnceTimes(at)

fun noneTimes(): NoneTimes = NoneTimes

/** The span `Date` can represent, in ms either side of the epoch. */
private const val MAX_MOMENT_MS = 8_640_000_000_000_000L

/** A Unix-ms value that names a real moment (the analogue of a finite, valid `Date`). */
private fun isMoment(ms: Long): Boolean = ms in -MAX_MOMENT_MS..MAX_MOMENT_MS

/**
 * Whether a schedule carries what its own variant needs to produce Occurrences.
 *
 * The rule an entry surface asks before saving, kept here rather than in the
 * form that asks it: what makes a schedule usable is a property of the schedule,
 * and a second surface — the Backlog, which sets one too — would otherwise write
 * its own copy of the answer.
 *
 * [NoneTimes] is well formed by definition; having no time is the whole point
 * of it. The rest need the field they are built around, and `daily`/`weekly`
 * need at least one usable moment: a `daily` schedule with no times produces
 * nothing, which is the misconfiguration `none` exists to replace.
 *
 * It is deliberately not a judgement about *when*. A `once` schedule whose
 * instant has passed is well formed and simply spent — that is the state every
 * one-off ends in, and it arrives that way from a pull.
 */
fun isWellFormedSchedule(schedule: ScheduleTimes): Boolean =
    when (schedule) {
        is DailyTimes -> schedule.times.any(::isUsableTime)
        is WeeklyTimes -> schedule.daysOfWeek.isNotEmpty() && schedule.times.any(::isUsableTime)
        is IntervalTimes -> schedule.hours > 0 && isMoment(schedule.startAt)
        is OnceTimes -> isMoment(schedule.at)
        NoneTimes -> true
    }

/** A `"HH:MM"` the Occurrence engine will accept. */
private fun isUsableTime(time: String): Boolean = parseTimeOnly(time) != null

private val WEEKDAY_NAMES =
    listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

/**
 * `TimeOnly` on the wire is `HH:mm:ss`, optionally with fractional ticks. The
 * POC's schedule engine works in `HH:MM`, so both are accepted on the way in
 * and minutes precision is what the domain keeps.
 */
private val TIME_ONLY_PATTERN = Regex("""([0-9]{1,2}):([0-9]{2})(?::[0-9]{2}(?:\.[0-9]+)?)?""")

private fun parseTimeOnly(value: String): String? {
    val match = TIME_ONLY_PATTERN.matchEntire(value) ?: return null
    val hour = match.groupValues[1].toInt()
    val minute = match.groupValues[2].toInt()
    if (hour > 23 || minute > 59) return null

    return "%02d:%02d".format(Locale.ROOT, hour, minute)
}

private fun JsonElement?.string(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number as a double, or null for anything that is not one. */
private fun JsonElement?.number(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    return if (primitive.isString) null else primitive.content.toDoubleOrNull()
}

private fun parseTimes(value: JsonElement?): List<String>? {
    if (value !is JsonArray) return null

    return value.map { parseTimeOnly(it.string() ?: return null) ?: return null }
}

/**
 * Reads a `DayOfWeek`.
 *
 * Both encodings the archived C# produced are accepted, because it wrote two:
 * `TaskRx.Data/DapperInitializer.cs` persists with options that carry only the
 * `ScheduleTimes` converter, so `DayOfWeek` lands as an ordinal, while
 * `apps/TaskRx.Maui/ReminderApiClient.cs:14` adds a global
 * `JsonStringEnumConverter`, which turns the same field into a name over HTTP.
 */
private fun parseWeekday(value: JsonElement): Int? {
    val primitive = value as? JsonPrimitive ?: return null

    if (!primitive.isString) {
        val number = primitive.content.toDoubleOrNull() ?: return null
        if (number % 1.0 != 0.0 || number < 0 || number > 6) return null
        return number.toInt()
    }

    val index = WEEKDAY_NAMES.indexOfFirst { it.equals(primitive.content, ignoreCase = true) }
    return if (index == -1) null else index
}

private fun parseWeekdays(value: JsonElement?): List<Int>? {
    if (value !is JsonArray) return null

    return value.map { parseWeekday(it) ?: return null }
}

private fun parseHours(value: JsonElement?): Int? {
    val number = value.number() ?: return null
    if (number % 1.0 != 0.0 || number <= 0 || number > Int.MAX_VALUE) return null
    return number.toInt()
}

private fun parseInstant(value: JsonElement?): Long? {
    val text = value.string() ?: return null
    return try {
        // `toEpochMilli` throws past the Long range, and `Date.parse` is NaN past
        // +-8.64e15: both are "not a moment".
        OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
            .takeIf(::isMoment)
    } catch (_: DateTimeParseException) {
        null
    } catch (_: ArithmeticException) {
        null
    }
}

/**
 * `Date.prototype.toISOString`: always milliseconds, always `Z`. Years outside
 * 0000-9999 get a sign and six digits, as the JS spec says; `uuuu` (proleptic
 * year) rather than `yyyy` (year-of-era) so a year before 1 is not folded onto a
 * positive one.
 */
private val ISO_MILLIS_UTC: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

private fun toIsoString(ms: Long): String {
    val instant = Instant.ofEpochMilli(ms)
    val year = instant.atZone(ZoneOffset.UTC).year
    val yearText =
        if (year in 0..9999) {
            "%04d".format(Locale.ROOT, year)
        } else {
            (if (year < 0) "-" else "+") + "%06d".format(Locale.ROOT, kotlin.math.abs(year))
        }
    return "$yearText-${ISO_MILLIS_UTC.format(instant)}"
}

private fun toTimeOnly(time: String): String {
    val normalized =
        parseTimeOnly(time)
            ?: throw IllegalArgumentException("Cannot serialize \"$time\" as a TimeOnly: expected \"HH:MM\".")
    return "$normalized:00"
}

/**
 * Writes the shape `ScheduleTimesJsonConverter.Write` produces: a `$type`
 * discriminator naming the C# record, then that record's members under their
 * PascalCase names.
 *
 * Instants are written the way `Date.toISOString()` does (`...Z`), which spells
 * the same instant .NET writes as `+00:00`; [parseScheduleTimes] reads either.
 *
 * Throws [IllegalArgumentException] if a time cannot be represented as a
 * `TimeOnly` — an invalid time has no wire encoding, and silently emitting
 * `"25:00:00"` would push the failure into the consumer.
 */
fun serializeScheduleTimes(schedule: ScheduleTimes): JsonObject =
    when (schedule) {
        is DailyTimes ->
            buildJsonObject {
                put("\$type", "DailyTimes")
                putJsonArray("Times") { schedule.times.forEach { add(toTimeOnly(it)) } }
            }
        is IntervalTimes ->
            buildJsonObject {
                put("\$type", "IntervalTimes")
                put("Hours", schedule.hours)
                put("StartAtUtc", toIsoString(schedule.startAt))
            }
        is WeeklyTimes ->
            buildJsonObject {
                put("\$type", "WeeklyTimes")
                putJsonArray("DaysOfWeek") { schedule.daysOfWeek.forEach { add(it) } }
                putJsonArray("Times") { schedule.times.forEach { add(toTimeOnly(it)) } }
            }
        is OnceTimes ->
            buildJsonObject {
                put("\$type", "OnceTimes")
                put("AtUtc", toIsoString(schedule.at))
            }
        NoneTimes -> buildJsonObject { put("\$type", "NoneTimes") }
    }

/**
 * Reads the wire shape back into the domain union.
 *
 * Returns `null` for anything it cannot fully validate — an absent or unknown
 * `$type`, a non-object, or a payload whose members are missing or malformed.
 * `null` rather than a throw mirrors `ScheduleTimesJsonConverter.Read`, which
 * returns `null` for an unrecognized discriminator and lets the caller decide
 * on a default.
 */
fun parseScheduleTimes(value: JsonElement?): ScheduleTimes? {
    if (value !is JsonObject) return null

    return when (value["\$type"].string()) {
        "DailyTimes" -> parseTimes(value["Times"])?.let(::dailyTimes)
        "IntervalTimes" -> {
            val hours = parseHours(value["Hours"])
            val startAt = parseInstant(value["StartAtUtc"])
            if (hours == null || startAt == null) null else intervalTimes(hours, startAt)
        }
        "WeeklyTimes" -> {
            val daysOfWeek = parseWeekdays(value["DaysOfWeek"])
            val times = parseTimes(value["Times"])
            if (daysOfWeek == null || times == null) null else weeklyTimes(daysOfWeek, times)
        }
        "OnceTimes" -> parseInstant(value["AtUtc"])?.let(::onceTimes)
        "NoneTimes" -> noneTimes()
        else -> null
    }
}
