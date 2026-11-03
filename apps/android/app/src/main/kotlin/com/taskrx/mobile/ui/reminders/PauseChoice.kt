package com.taskrx.mobile.ui.reminders

import com.taskrx.mobile.ui.forms.pickedInstant
import java.time.ZoneId

/** A pause as the dialog resolves it: `[from, until)`, with `until` null until the person resumes. */
data class PauseChoice(val from: Long, val until: Long?)

/**
 * The window a pause dialog's two date choices mean, or null when they contradict.
 *
 * Both dates come from the date picker, which answers in UTC midnight (see [pickedInstant]).
 * No start day, or today, starts now: a pause is never backdated, because the sweep would then
 * have already judged the doses it covers. A start day later than today begins at that
 * day's first moment. The resume day is the first day doses come back, so it is that day's
 * first moment too, and it must fall after the start or there is nothing to pause.
 */
fun resolvePause(
    startDateMillis: Long?,
    resumeDateMillis: Long?,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): PauseChoice? {
    val from = maxOf(now, startDateMillis?.let { pickedInstant(it, 0, 0, zone) } ?: now)
    val until = resumeDateMillis?.let { pickedInstant(it, 0, 0, zone) }
    if (until != null && until <= from) return null
    return PauseChoice(from, until)
}
