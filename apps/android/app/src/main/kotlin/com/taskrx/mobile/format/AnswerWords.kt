package com.taskrx.mobile.format

import com.taskrx.core.domain.ReminderKind
import kotlin.math.abs

/** How far a snooze defers a moment. Named once; the button copy reads it too. */
const val SNOOZE_MINUTES: Int = 15

/**
 * Every Kind-specific word on the answer screen, decided once. Mirrors `wordsFor` in
 * archive/expo-rn:apps/mobile/app/(app)/dose/log.tsx.
 *
 * ADR 0001 made the stored vocabulary Kind-neutral so exactly one place per surface has
 * to know the difference, and this is that place for the answer screen. "Dose" appears
 * in none of them: CONTEXT.md lists it under _Avoid_ for "a recorded taking". What these
 * confirm is a Reminder Instance, and ADR 0001 licenses showing a medication as taken.
 */
data class KindWords(
    val badge: String,
    val due: String,
    val complete: String,
    val completed: String,
    val skipped: String,
)

fun wordsFor(kind: ReminderKind): KindWords =
    when (kind) {
        ReminderKind.Medication ->
            KindWords(
                badge = "MEDICATION",
                due = "Time to take",
                complete = "Mark taken",
                completed = "Recorded as taken",
                skipped = "Recorded as skipped",
            )
        ReminderKind.Task ->
            KindWords(
                badge = "TASK",
                due = "Time to do it",
                complete = "Mark done",
                completed = "Recorded as done",
                skipped = "Recorded as skipped",
            )
    }

/** How far the moment is from [now]: ahead, within a minute either way, or overdue. */
fun urgencyLabel(scheduledTime: Long, now: Long, words: KindWords): String {
    // JavaScript's Math.round (halves go up) over the signed difference.
    val diffMinutes = Math.floorDiv(2 * (now - scheduledTime) + 60_000, 120_000L)
    return when {
        diffMinutes < -1 -> "Due in ${abs(diffMinutes)} minutes"
        diffMinutes <= 1 -> words.due
        else -> "Overdue by $diffMinutes minutes"
    }
}

/** Past this, the urgency line is drawn as overdue. */
const val OVERDUE_AFTER_MS: Long = 5 * 60_000L

fun isOverdue(scheduledTime: Long, now: Long): Boolean = scheduledTime < now - OVERDUE_AFTER_MS
