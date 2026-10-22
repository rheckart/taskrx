package com.taskrx.mobile.settings

import android.content.Context
import com.taskrx.core.snooze.SNOOZE_CHOICES_MINUTES
import com.taskrx.core.snooze.snoozeMinutesOrDefault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the person has chosen in Settings, kept on this phone only.
 *
 * Not synced: a choice about how this phone behaves is not data about a reminder, and
 * syncing one number would mean a server schema change. What counts as a valid choice,
 * and what an unset or unrecognised value reads as, is :core's `snoozeMinutesOrDefault`.
 */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("taskrx.settings", Context.MODE_PRIVATE)

    private val snooze =
        MutableStateFlow(snoozeMinutesOrDefault(if (prefs.contains(SNOOZE_KEY)) prefs.getInt(SNOOZE_KEY, 0) else null))

    /** How long a snooze lasts, in minutes. Every snooze button reads this at the moment it is pressed. */
    val snoozeMinutes: StateFlow<Int> = snooze

    fun setSnoozeMinutes(minutes: Int) {
        require(minutes in SNOOZE_CHOICES_MINUTES) { "$minutes is not an offered snooze length" }
        // commit(), not apply(): the next process start reads this file, and a snooze pressed
        // from the shade right after must not see the old value.
        prefs.edit().putInt(SNOOZE_KEY, minutes).commit()
        snooze.value = minutes
    }

    private companion object {
        const val SNOOZE_KEY = "snooze_minutes"
    }
}
