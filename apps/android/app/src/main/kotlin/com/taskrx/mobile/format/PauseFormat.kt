package com.taskrx.mobile.format

import com.taskrx.core.domain.PauseWindow
import java.time.ZoneId
import java.util.Locale

/**
 * What a Reminder's live pause says on its row: "Paused until Fri, Nov 6", "Paused until you
 * resume", or, for one that has not started, "Pause starts Mon, Nov 2 · back Fri, Nov 6".
 * The resume day is the first day doses return, so "until" is said of the day itself.
 */
fun pauseLine(
    pause: PauseWindow,
    now: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val back = pause.until?.let { formatDay(it, zone, locale) }
    return if (pause.from <= now) {
        if (back == null) "Paused until you resume" else "Paused until $back"
    } else {
        val starts = "Pause starts ${formatDay(pause.from, zone, locale)}"
        if (back == null) starts else "$starts · back $back"
    }
}
