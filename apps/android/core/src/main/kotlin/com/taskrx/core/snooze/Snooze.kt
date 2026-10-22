package com.taskrx.core.snooze

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/*
 * How long a snooze lasts, and when "pick a time" may put a moment off to.
 *
 * Both are rules, so both live here and not in a screen. The app stores the person's
 * choice; what counts as a valid choice, and what to fall back to when the stored value
 * is not one, is decided once.
 *
 * Ported in spirit from Medisafe's `snooze_min` setting (default 10, picker 5 to 120) and
 * its "Reschedule for" action. The default here is 15, what the app has always used, so
 * installing this changes nothing for someone who never opens the setting.
 *
 * Pure: no clock, no storage. The caller supplies `now`.
 */

/** The lengths the setting offers, in minutes, shortest first. */
val SNOOZE_CHOICES_MINUTES: List<Int> = listOf(5, 10, 15, 30, 60)

/** What a snooze lasts until the person picks another length. */
const val DEFAULT_SNOOZE_MINUTES: Int = 15

/**
 * The length to use given what is [stored], which is null when nothing was ever saved.
 * A value that is not one of the [SNOOZE_CHOICES_MINUTES] (a build that offered other
 * lengths, a corrupted file) falls back to the default rather than being trusted.
 */
fun snoozeMinutesOrDefault(stored: Int?): Int =
    stored?.takeIf { it in SNOOZE_CHOICES_MINUTES } ?: DEFAULT_SNOOZE_MINUTES

/** The instant a snooze of [minutes] taken at [now] runs out. */
fun snoozeUntil(now: Long, minutes: Int): Long = now + minutes * 60_000L

/**
 * The instant for [hour]:[minute] today, in [zone], when that is still ahead of [now];
 * null when it is not, or when the pair is not a clock time.
 *
 * "Today" is the local day holding [now]. A picked time is never tomorrow's: a moment put
 * off past midnight belongs to the next day's timeline, and the sweep would otherwise be
 * asked to judge a deferral that spans two of them. The clock time is resolved on today's
 * date, so on a day a clock change skips an hour, a time inside the gap lands an hour
 * later, as the zone rules say, rather than being refused.
 *
 * Equal to [now] is not ahead: a deferral to the instant it was made would fire at once.
 */
fun rescheduleInstant(now: Long, hour: Int, minute: Int, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (hour !in 0..23 || minute !in 0..59) return null
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val picked = ZonedDateTime.of(today, LocalTime.of(hour, minute), zone).toInstant().toEpochMilli()
    return if (picked > now) picked else null
}

/**
 * Whether any clock time is left to pick today: false only in the last minute of the day,
 * when even 23:59 is not ahead of [now]. The screen hides the action then, since a picker
 * with no valid answer is a dead end.
 */
fun canPickATimeToday(now: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean = rescheduleInstant(now, 23, 59, zone) != null
