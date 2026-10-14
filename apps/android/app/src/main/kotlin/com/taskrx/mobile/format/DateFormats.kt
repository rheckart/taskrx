package com.taskrx.mobile.format

import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * How this app writes a moment. Mirrors archive/expo-rn:apps/mobile/src/format/datetime.ts: one
 * definition rather than three, because the answer screen, the timeline and the
 * supplies list all show the same instant in the same shape.
 *
 * Presentation, so it lives here and not in :core: it reaches for the device locale
 * and the device zone, which is exactly what a pure, replayable module must not do.
 * Both are parameters so a test can pin them.
 */

/** Renders [ms] with the locale's best pattern for [skeleton] (`jj` honours the locale's hour cycle). */
private fun render(ms: Long, skeleton: String, zone: ZoneId, locale: Locale): String {
    val pattern = DateFormat.getBestDateTimePattern(locale, skeleton)
    return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.ofEpochMilli(ms))
}

/** "Aug 4, 09:00": the day and the time, for a moment shown on its own. */
fun formatDayTime(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    render(ms, "MMMdjjmm", zone, locale)

/** "Aug 4, 2026, 09:00": the day with its year, for a moment that can be far ahead and is read alone. */
fun formatFullDayTime(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    render(ms, "yMMMdjjmm", zone, locale)

/** "09:00": the time alone, for a row already under a day heading. */
fun formatTime(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    render(ms, "jjmm", zone, locale)

/**
 * "Tue, Aug 4". The weekday earns its place: the pattern a person looks for in a week
 * is usually "the weekend", and on a refill it is "the day the pharmacy is shut".
 */
fun formatDay(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    render(ms, "EEEMMMd", zone, locale)

/**
 * Local midnight of the day a `"YYYY-MM-DD"` key names. Built from the calendar date in
 * [zone], never by parsing the key as an instant: a date-only string is UTC midnight,
 * which renders as the day before in every zone behind UTC.
 */
fun dayKeyToLocalMs(dayKey: String, zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDate.parse(dayKey).atStartOfDay(zone).toInstant().toEpochMilli()

/** The same day [formatDay] names, from an adherence bucket key rather than an instant. */
fun formatDayKey(dayKey: String, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    formatDay(dayKeyToLocalMs(dayKey, zone), zone, locale)

/**
 * The Today heading for a day [offset] days from today. Beyond the neighbours the day is
 * named from [windowStart], the local midnight the timeline was projected over.
 */
fun dayHeading(
    offset: Int,
    windowStart: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String =
    when (offset) {
        0 -> "Today"
        -1 -> "Yesterday"
        1 -> "Tomorrow"
        else -> formatDay(windowStart, zone, locale)
    }

/** "Aug 4": a day with no time and no weekday, for a history heading further back than this week. */
fun formatMonthDay(ms: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
    render(ms, "MMMd", zone, locale)
