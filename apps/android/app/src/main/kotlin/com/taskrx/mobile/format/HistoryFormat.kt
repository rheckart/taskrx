package com.taskrx.mobile.format

import com.taskrx.core.domain.InstanceStatus
import com.taskrx.core.domain.ReminderKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/*
 * The words on the history list. Mirrors archive/expo-rn:apps/mobile/app/(app)/dose/history.tsx.
 *
 * Presentation only: which Instances exist and what status each has is decided elsewhere.
 * This is the one place a Kind check on status is allowed on this surface (ADR 0001): a
 * medication that is Completed is SHOWN as taken, a task as done.
 */

/**
 * "Taken" or "Done", not "Completed". `snoozed` is its own word and is never folded into
 * `skipped` or `missed`: the Adherence figure counts those against the person and a snooze
 * is a decision rather than a lapse, so naming it as one would contradict the number on
 * the other screen.
 */
fun historyStatusLabel(status: InstanceStatus, kind: ReminderKind): String =
    when (status) {
        InstanceStatus.Completed -> if (kind == ReminderKind.Medication) "Taken" else "Done"
        InstanceStatus.Snoozed -> "Snoozed"
        InstanceStatus.Skipped -> "Skipped"
        InstanceStatus.Missed -> "Missed"
        InstanceStatus.Pending -> "Pending"
    }

/** The label before the time an Instance was completed: "Taken: 09:02" / "Done: 09:02". */
fun completedAtLabel(kind: ReminderKind): String = if (kind == ReminderKind.Medication) "Taken" else "Done"

/**
 * The heading a moment is filed under: Today, Yesterday, This week, or its date.
 *
 * The week starts on Sunday, as the Expo app counted it. "This week" is anything on or
 * after that Sunday that is not today or yesterday, so on a Monday it holds only the
 * Sunday and on a Sunday nothing at all.
 */
fun historyDayLabel(
    ms: Long,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val day = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    if (day == today) return "Today"
    if (day == today.minusDays(1)) return "Yesterday"

    val startOfWeek = today.minusDays((today.dayOfWeek.value % DayOfWeek.SUNDAY.value).toLong())
    if (!day.isBefore(startOfWeek)) return "This week"

    return formatMonthDay(ms, zone, locale)
}

data class DayGroup<T>(val title: String, val items: List<T>)

/**
 * [items] filed under their heading, headings in the order they first appear. Callers pass
 * newest first, so the headings read newest first too.
 */
fun <T> groupByDay(
    items: List<T>,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    timeOf: (T) -> Long,
): List<DayGroup<T>> {
    val groups = LinkedHashMap<String, MutableList<T>>()
    for (item in items) {
        groups.getOrPut(historyDayLabel(timeOf(item), now, zone, locale)) { mutableListOf() }.add(item)
    }
    return groups.map { (title, list) -> DayGroup(title, list) }
}
